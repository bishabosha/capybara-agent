package capybara.agent

class WorkshopSetupSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  test("fresh setup includes both starters, hidden CI files and portable shell environments") {
    val temporary = os.temp.dir(prefix = "capybara-workshop-setup-")
    try
      val directory = temporary / "participant's workshop"
      val workspaces = WorkshopSetup.create(os.pwd, directory)
      assertEquals(workspaces.map(_.name), Seq("editing", "github"))
      assert(workspaces.forall(_.created))
      assertEquals(os.read(directory / "editing/Greeting.scala"), os.read(os.pwd / "examples/editing-workspace/Greeting.scala"))
      assertEquals(os.read(directory / "github/src/word-count.mjs"),
        os.read(os.pwd / "presentations/capybara-architecture/workshop/project/src/word-count.mjs"))
      assert(os.exists(directory / "github/.github/workflows/ci.yml"))
      for workspace <- workspaces do
        val command = s". ${WorkshopSetup.shellQuote(workspace.environment.toString)}; printf '%s' \"$$CAPYBARA_WORKSPACE_ROOT\""
        val sourced = os.proc("sh", "-c", command).call().out.text()
        assertEquals(sourced, workspace.root.toString)
        assert(!os.read(workspace.environment).contains("TOKEN"))
    finally os.remove.all(temporary)
  }

  test("repeating setup preserves edited files and participant additions") {
    val directory = os.temp.dir(prefix = "capybara-workshop-repeat-")
    try
      val _ = WorkshopSetup.create(os.pwd, directory)
      os.write.over(directory / "editing/Greeting.scala", "participant edit")
      os.write.over(directory / "github/src/word-count.mjs", "implemented")
      os.write(directory / "github/notes.txt", "keep this")
      val repeated = WorkshopSetup.create(os.pwd, directory)
      assert(repeated.forall(!_.created))
      assertEquals(os.read(directory / "editing/Greeting.scala"), "participant edit")
      assertEquals(os.read(directory / "github/src/word-count.mjs"), "implemented")
      assertEquals(os.read(directory / "github/notes.txt"), "keep this")
    finally os.remove.all(directory)
  }

  test("conflicting files fail before any workspace is copied") {
    val directory = os.temp.dir(prefix = "capybara-workshop-conflict-")
    try
      os.write(directory / "github.env", "participant settings")
      val _ = intercept[IllegalArgumentException](WorkshopSetup.create(os.pwd, directory))
      assert(!os.exists(directory / "editing"))
      assert(!os.exists(directory / "github"))
      assertEquals(os.read(directory / "github.env"), "participant settings")
    finally os.remove.all(directory)
  }

  test("workshop-setup CLI prepares builds and prints ordinary skill-run commands") {
    val directory = os.temp.dir(prefix = "capybara-workshop-cli-")
    val output = new java.io.ByteArrayOutputStream()
    try
      scala.Console.withOut(output) {
        capybara.agent.run("workshop-setup", "--directory", directory.toString)
      }
      val text = output.toString(java.nio.charset.StandardCharsets.UTF_8)
      assert(text.contains("Workshop ready"))
      assert(text.contains("skill-run --skill editing --file examples/snippets/editing.scala"))
      assert(text.contains("skill-run --skill editing,github --file examples/snippets/github-lab.scala"))
      assert(os.exists(directory / "editing.env"))
      assert(os.exists(directory / "github.env"))
    finally os.remove.all(directory)
  }
