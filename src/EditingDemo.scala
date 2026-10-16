package capybara.agent

import steps.result.Result

/** Generic text-file editing through the actual safe REPL and manifest wiring. */
object EditingDemo:
  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => throw IllegalStateException(error)

  @mainargs.main(doc = "Demonstrate generic text editing through the editing capability, without a model or external runtime.")
  def main(): Unit =
    val root = os.temp.dir(prefix = "capybara-editing-demo-")
    try
      os.write(root / "Greeting.scala", "val greeting = \"hello, world\"\n")
      os.write(root / "README.md", "A generic text-editing demonstration.\n")
      val original = loaded("skills/filesystem")
      val factory = s"capybara.skills.filesystem.internal.configured(${ujson.write(root.toString)})"
      val filesystem = original.copy(bindings = original.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = factory)))
      val skills = Seq(filesystem, loaded("skills/editing"))
      val code = os.read(os.pwd / "examples/snippets/editing.scala")
      val builtins = loaded("builtin-skills/capybara-builtins")
      val output = ReplExec.runCode(builtins, skills, code, CancellationToken.Never) match
        case Result.Ok(value) => value
        case Result.Err(error) => throw IllegalStateException(error)
      require(!output.failed, s"Coding demo failed: ${output.stderr}")
      val values = output.stdout.linesIterator.map(ujson.read(_)("special.success")).toVector
      require(values.size == 4 && values(1).str.contains("hello, world") &&
        values(2).str.contains("hello, capybara"), "Demo did not apply the text edit")
      scala.Console.println(s"Files: ${values(0).arr.map(_.str).mkString(", ")}")
      scala.Console.println(s"Before: ${values(1).str.trim}")
      scala.Console.println(s"After: ${values(2).str.trim}")
      scala.Console.println(s"Created notes.txt: ${values(3).str.trim}")
      val rejected = ReplExec.runCode(builtins, skills,
        "editing.edit(\"Greeting.scala\")(text => { puts(text); text })", CancellationToken.Never)
      require(rejected.isInstanceOf[Result.Err[?]], "Expected the capture checker to reject captured output")
      scala.Console.println("Rejected: edit callback captures output")
      scala.Console.println(s"Workspace kept at: $root")
    catch
      case error: Throwable =>
        os.remove.all(root)
        throw error
