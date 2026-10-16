package capybara.agent

import steps.result.Result
import OllamaClient.{ChatMessage, ResolvedChunk, ToolCall}
import ScalaAgent.{ScalaToolCall, SkillNames, ToolArguments}
import upickle.default.*

class SkillDiscoverySuite extends munit.FunSuite:
  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)
  private lazy val builtins = loaded("builtin-skills/capybara-builtins")
  private lazy val editing = loaded("skills/editing")
  private lazy val github = loaded("skills/github")
  private lazy val inspection = loaded("skills/inspect-scala-api")
  private lazy val filesystem = loaded("skills/filesystem")
  private lazy val available = Seq(Skills.Basic, editing, filesystem, github, inspection)

  test("initial context has stable sorted names and no descriptions or interfaces") {
    val prompt = SkillDiscovery.systemPrompt(available)
    assertEquals(prompt, SkillDiscovery.systemPrompt(available.reverse))
    assert(prompt.contains("basic, editing, filesystem, github, inspect-scala-api"))
    assert(prompt.contains("puts(value)"))
    available.filter(_.requiresRuntimeClasspath).foreach { skill =>
      assert(!prompt.contains(skill.description))
      assert(!prompt.contains(skill.interface))
      skill.bindings.foreach(binding => assert(!prompt.contains(binding.value)))
    }
    assert(prompt.length < 3000)
  }

  test("editing instructions include filesystem first and names match public packages") {
    val text = SkillDiscovery.read(Seq("editing"), available, builtins) match
      case Result.Ok(value) => value
      case Result.Err(error) => fail(error)
    assert(text.indexOf("#### filesystem") < text.indexOf("#### editing"))
    assert(text.contains("filesystem: capybara.skills.filesystem.FileSystem"))
    assert(text.contains("editing: capybara.skills.editing.TextEditor"))
    assert(text.contains("Required skills (loaded automatically): filesystem"))
    for skill <- Seq(filesystem, editing, github, inspection) do
      assertEquals(skill.name, skill.universe)
      val words = skill.name.split("-")
      val capabilityName = words.head + words.tail.map(_.capitalize).mkString
      assertEquals(skill.bindings.head.name, capabilityName)
      assert(skill.bindings.head.tpe.startsWith(s"capybara.skills.${skill.name.replace("-", "")}."))
    assert(!available.exists(_.universe == "coding"))
  }

  test("descriptions browse metadata only and validate names without partial results") {
    val description = SkillDiscovery.describe(Seq("editing"), available)
    assertEquals(description, Result.Ok(s"editing: ${editing.description}"))
    assertEquals(SkillDiscovery.describe(Nil, available), SkillDiscovery.describe(Nil, available.reverse))
    assert(SkillDiscovery.describe(Seq("editing", "missing"), available).isInstanceOf[Result.Err[?]])
  }

  test("reading a skill discloses binding names and dependencies but leaves signatures to Cellar") {
    val text = SkillDiscovery.read(Seq("github", "github"), available, builtins) match
      case Result.Ok(value) => value
      case Result.Err(error) => fail(error)
    assert(text.contains("filesystem: capybara.skills.filesystem.FileSystem"))
    assert(text.contains("github: capybara.skills.github.GitHub"))
    assert(text.contains("inspectScalaApi.get"))
    assert(text.contains("write only the snippet body"))
    assertEquals(text.sliding("#### github".length).count(_ == "#### github"), 1)
    assert(!text.contains("#### editing"))
    Seq(filesystem, github).foreach { skill =>
      assert(!text.contains(skill.interface))
      assert(!text.contains(skill.code))
      skill.bindings.foreach(binding => assert(!text.contains(binding.value)))
    }
    assert(SkillDiscovery.read(Nil, available, builtins).isInstanceOf[Result.Err[?]])
    assert(SkillDiscovery.read(Seq("missing"), available, builtins).isInstanceOf[Result.Err[?]])
  }

  test("tool-specific schemas and parsers preserve legacy execution arguments") {
    val definitions = ScalaAgent.toolDefinitions()
    assertEquals(definitions.tools.map(_.function.name), Vector("run_scala_code", "describe_skills", "read_skills"))
    val names = read[ToolArguments](ujson.Obj("universes" -> ujson.Arr("editing")))(using definitions.toolParsers("read_skills"))
    assertEquals(names, SkillNames(Seq("editing")))
    val execution = read[ToolArguments]("""{"universe":"basic","scala_code":"puts(1)"}""")
    assertEquals(execution, ScalaToolCall(scala_code = "puts(1)"))
    val _ = intercept[Exception] {
      read[ToolArguments](ujson.Obj("universes" -> ujson.Arr("editing")))(using definitions.toolParsers("run_scala_code"))
    }
    val properties = definitions.tools.find(_.function.name == "read_skills").get.function.parameters("properties")
    assertEquals(properties.obj.keySet.toSet, Set("universes"))
    assertEquals(ScalaAgent.toolDefinitions(native = true).tools.map(_.function.name), Vector("run_scala_code"))
  }

  test("Gemma's untagged discovery chunk decodes through the Ollama tool parser") {
    val line = """{"model":"gemma4:12b-mlx","created_at":"2026-10-16T15:29:34.185496Z","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_hz46gug9","function":{"index":0,"name":"describe_skills","arguments":{"universes":["filesystem"]}}}]},"done":false}"""
    val definitions = ScalaAgent.toolDefinitions()
    for name <- Seq("describe_skills", "read_skills") do
      OllamaClient.chunkParser(line.replace("describe_skills", name), definitions.toolParsers) match
        case Result.Ok((OllamaClient.Chunk.Tools(calls), done, _)) =>
          assert(!done)
          assertEquals(calls.map(_.name), Vector(name))
          assertEquals(calls.map(_.arguments), Vector[ToolArguments](SkillNames(Seq("filesystem"))))
        case other => fail(s"Could not decode $name: $other")
  }

  test("execution chunks and every argument writer use ordinary objects without a type discriminator") {
    val definitions = ScalaAgent.toolDefinitions()
    val arguments = ujson.Obj("scala_code" -> "puts(1)")
    val line = ujson.write(ujson.Obj("done" -> false, "message" -> ujson.Obj(
      "content" -> "", "tool_calls" -> ujson.Arr(ujson.Obj("function" -> ujson.Obj(
        "name" -> "run_scala_code", "arguments" -> arguments))))))
    OllamaClient.chunkParser(line, definitions.toolParsers) match
      case Result.Ok((OllamaClient.Chunk.Tools(calls), _, _)) =>
        assertEquals(calls.map(_.arguments), Vector[ToolArguments](ScalaToolCall(scala_code = "puts(1)")))
      case other => fail(s"Could not decode execution: $other")

    val examples: Seq[(String, ToolArguments, ujson.Value)] = Seq(
      ("describe_skills", SkillNames(Seq("filesystem")), ujson.Obj("universes" -> ujson.Arr("filesystem"))),
      ("read_skills", SkillNames(Seq("editing")), ujson.Obj("universes" -> ujson.Arr("editing"))),
      ("run_scala_code", ScalaToolCall(scala_code = "puts(1)"),
        ujson.Obj("universe" -> "basic", "scala_code" -> "puts(1)", "universes" -> ujson.Arr()))
    )
    for (name, call, json) <- examples do
      assertEquals(writeJs(call), json)
      assertEquals(writeJs(call)(using definitions.toolParsers(name)), json)
      assertEquals(OllamaClient.parseArguments(name, json, definitions.toolParsers), Result.Ok(call))
      assertEquals(read[ToolArguments](writeJs(call)), call)
    assertEquals(writeJs(SkillNames(Seq("editing"))), examples(1)._3)
    assertEquals(writeJs(ScalaToolCall(scala_code = "puts(1)")), examples(2)._3)
    definitions.tools.foreach { tool =>
      assert(!tool.function.parameters("properties").obj.contains("$type"))
    }
  }

  test("tool-specific decoders preserve defaults and reject malformed field types") {
    val definitions = ScalaAgent.toolDefinitions()
    for name <- Seq("describe_skills", "read_skills") do
      assertEquals(OllamaClient.parseArguments(name, ujson.Obj(), definitions.toolParsers),
        Result.Ok[ToolArguments](SkillNames()))
      for invalid <- Seq[ujson.Value](ujson.Str("filesystem"), ujson.Null, ujson.Arr(1)) do
        assert(OllamaClient.parseArguments(name, ujson.Obj("universes" -> invalid), definitions.toolParsers)
          .isInstanceOf[Result.Err[?]])
    for invalid <- Seq[ujson.Value](ujson.Obj(), ujson.Obj("scala_code" -> 1),
        ujson.Obj("scala_code" -> "()", "universe" -> ujson.Arr()),
        ujson.Obj("scala_code" -> "()", "universes" -> "editing")) do
      assert(OllamaClient.parseArguments("run_scala_code", invalid, definitions.toolParsers)
        .isInstanceOf[Result.Err[?]])
  }

  test("discovery appends tool calls and results while preserving the previous conversation prefix") {
    val previous = Vector(ChatMessage.user("Read a file"))
    val names = SkillNames(Seq("editing"))
    val result = ScalaAgent.discoveryResult("read_skills", names, available, builtins)
    val chunks: Seq[ScalaAgent.ScalaResolvedChunk] = Seq(ResolvedChunk.Tools(Vector(
      (ToolCall[ToolArguments]("read_skills", names, Some("discovery-1")), result))))
    val history = ScalaAgent.appendCollectedChunks(previous, chunks)
    assertEquals(history.take(previous.size), previous)
    assertEquals(history.size, previous.size + 2)
    assertEquals(history(1).json("tool_calls")(0)("function")("arguments"), writeJs(names))
    assertEquals(history(2).json("tool_call_id").str, "discovery-1")
    assertEquals(ujson.read(history(2).json("content").str)("success"), ujson.Bool(true))
    assert(ScalaAgent.hasToolCalls(chunks))
  }
