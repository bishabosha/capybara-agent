package capybara.agent

class CodingDemoSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  test("coding-demo edits ordinary text and demonstrates a real capture-checking failure") {
    val bytes = new java.io.ByteArrayOutputStream()
    val stream = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)
    scala.Console.withOut(stream) { capybara.agent.run("coding-demo") }
    val output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
    val lines = output.linesIterator.toVector
    val root = os.Path(lines.last.stripPrefix("Workspace kept at: "))
    val transcript = lines.dropRight(1).mkString("", "\n", "\n")
    try
      assert(transcript.contains("Before: val greeting = \"hello, world\""))
      assert(transcript.contains("After: val greeting = \"hello, capybara\""))
      assert(transcript.contains("Rejected: edit callback captures output"))
      assertEquals(os.read(root / "notes.txt"), "Edited through the coding capability.\n")
      assertEquals(os.read(root / "Greeting.scala"), "val greeting = \"hello, capybara\"\n")
    finally os.remove.all(root)
  }

  test("ordinary skill-run executes the checked-in coding snippet") {
    val root = os.temp.dir(prefix = "capybara-cli-edit-")
    try
      for file <- os.list(os.pwd / "examples/coding-workspace") do os.copy(file, root / file.last)
      val skill = Skills.loadFrom(os.pwd / "skills/coding") match
        case steps.result.Result.Ok(value) => value
        case steps.result.Result.Err(error) => fail(error)
      val configured = skill.copy(bindings = skill.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = s"editing.internal.configured(${ujson.write(root.toString)})")))
      val output = SkillRunFixture.run(Seq(configured), os.pwd / "examples/snippets/coding.scala")
      assertEquals(output.stderr, "")
      assertEquals(os.read(root / "Greeting.scala"), "val greeting = \"hello, capybara\"\n")
      assertEquals(os.read(root / "notes.txt"), "Edited through the coding capability.\n")
      if os.exists(os.pwd / ".agent-runtime/validate-presentation") then
        os.write.over(os.pwd / "presentations/capybara-architecture/public/assets/coding-demo.txt", output.stdout)
    finally os.remove.all(root)
  }
