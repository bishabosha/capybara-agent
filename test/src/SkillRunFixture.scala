package capybara.agent

/** Configure trusted factories for CLI tests; public interfaces and snippets stay unchanged. */
object SkillRunFixture:
  def run(skills: Seq[Skill], snippet: os.Path): ReplExec.CodeOutput =
    val root = os.temp.dir(prefix = "capybara-cli-skills-")
    val stdout = new java.io.ByteArrayOutputStream()
    val stderr = new java.io.ByteArrayOutputStream()
    try
      val directories = skills.map { skill =>
        val directory = root / skill.universe
        os.makeDir.all(directory / "scripts")
        os.write(directory / "SKILL.md", s"---\nname: ${skill.name}\ndescription: CLI fixture\n---\n")
        os.write(directory / "scripts/Interface.scala", skill.interface)
        os.write(directory / "scripts/Implementation.scala", skill.code)
        def quoted(text: String): String = ujson.write(text)
        val bindings = skill.bindings.map(b =>
          s"(name = ${quoted(b.name)}, tpe = ${quoted(b.tpe)}, value = ${quoted(b.value)})").mkString("Vector(", ",", ")")
        val requires = if skill.requires.isEmpty then "" else
          skill.requires.map(quoted).mkString(", requires = Vector(", ",", ")")
        os.write(directory / "scripts/Manifest.scala",
          s"val Manifest = (universe = ${quoted(skill.universe)}, api = ${quoted(skill.api)}, " +
          s"predef = ${quoted(skill.predef.getOrElse(""))}, bindings = $bindings$requires)")
        directory.toString
      }
      scala.Console.withOut(stdout) {
        scala.Console.withErr(stderr) {
          capybara.agent.run("skill-run", "--skill", directories.mkString(","), "--file", snippet.toString)
        }
      }
      ReplExec.CodeOutput(stdout.toString(java.nio.charset.StandardCharsets.UTF_8),
        stderr.toString(java.nio.charset.StandardCharsets.UTF_8))
    finally os.remove.all(root)
