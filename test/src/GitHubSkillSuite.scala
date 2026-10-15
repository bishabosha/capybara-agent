package capybara.agent

import steps.result.Result

/** Production safe compiler, scaffold, protocol and process boundary; deterministic GitHub transport. */
class GitHubSkillSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)

  private lazy val builtins = loaded("builtin-skills/capybara-builtins")
  private val token = "SYNTHETIC_HOST_TOKEN_abcdefghijklmnopqrstuvwxyz"
  private lazy val node = os.proc("/usr/bin/which", "node").call().out.text().trim

  private def withGitHub(test: (Skill, os.Path) => Unit): Unit =
    val temp = os.temp.dir(prefix = "capybara-github-skill-")
    try
      val profile = temp / "policy.json"
      os.write(profile, ujson.write(ujson.Obj(
        "version" -> 1, "repository" -> "example/lab", "base" -> "main",
        "read" -> ujson.Arr("README.md", "src/word-count.mjs", "test/word-count.test.mjs"),
        "write" -> ujson.Arr("src/word-count.mjs"), "allow_untested_draft" -> false,
        "test" -> ujson.Obj("kind" -> "node", "entry" -> "test/word-count.test.mjs", "executable" -> node)
      )))
      os.perms.set(profile, "rw-------")
      val original = loaded("skills/github")
      val arguments = Seq(profile.toString, "/usr/bin/python3",
        (os.pwd / "test/fixtures/github_fixture.py").toString, token).map(ujson.write(_)).mkString(", ")
      val factory = s"githubskill.internal.configured($arguments)"
      val skill = original.copy(bindings = original.bindings.map(b => (name = b.name, tpe = b.tpe, value = factory)))
      test(skill, profile)
    finally os.remove.all(temp)

  private def run(skill: Skill, code: String): ReplExec.CodeOutput =
    ReplExec.runCode(builtins, skill, code) match
      case Result.Ok(output) =>
        assert(!output.stdout.contains(token))
        assert(!output.stderr.contains(token))
        output
      case Result.Err(error) => fail(error)

  test("GitHub backend policy, transport, retry and real sandbox regressions") {
    val result = os.proc("/usr/bin/python3", "scripts/test_github_workflow.py")
      .call(check = false, timeout = 60000)
    assertEquals(result.exitCode, 0, result.out.text() + result.err.text())
  }

  test("GitHub skill rejects captured capabilities and credential escape APIs") {
    withGitHub { (skill, _) =>
      for code <- Seq(
        "github.transform(\"src/word-count.mjs\")(source => { puts(source); source })",
        "github.transform(\"src/word-count.mjs\")(source => github.read(\"README.md\"))",
        "puts(github.token)",
        "github.request(\"https://attacker.invalid\")",
        "githubskill.internal.impl()",
        "puts(sys.env(\"CAPYBARA_GITHUB_TOKEN\"))",
        "puts(java.lang.System.getenv(\"CAPYBARA_GITHUB_TOKEN\"))",
        "puts(github.getClass.getDeclaredFields.toList)",
        "puts(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))",
        "new java.lang.ProcessBuilder(\"env\").start()"
      ) do
        val result = ReplExec.runCode(builtins, skill, code)
        assert(result.isInstanceOf[Result.Err[?]], s"Unsafe code was admitted: $code -> $result")
      assert(ScalaAgent.availableSkills.exists(_.universe == "github"))
      assert(!run(skill, "puts(github)").failed)
    }
  }

  test("safe tool calls look up an issue, implement, test, push and create one draft PR") {
    assume(System.getProperty("os.name").contains("Mac"))
    withGitHub { (skill, profile) =>
      val output = run(skill, os.read(os.pwd / "examples/snippets/github-lab.scala"))
      assert(!output.failed, output.stderr)
      val values = output.stdout.linesIterator.map(ujson.read(_)("special.success")).toVector
      assertEquals(values(0)("number").num.toInt, 1)
      assertEquals(values(2)("status").str, "failed")
      assertEquals(values(3)("status").str, "passed")
      assertEquals(values(6)("url").str, "https://github.com/example/lab/pull/2")
      assertEquals(values(7)("status").str, "pending")
      val state = ujson.read(os.read(profile / os.up / "policy.remote.json"))
      assertEquals(state("pulls").arr.size, 1)
      assert(state("pulls")(0)("draft").bool)
      val blocked = run(skill, "github.read(\".env\")")
      assert(blocked.failed)
      assert(blocked.stderr.contains("PATH_DENIED"))
      val leak = run(skill, s"github.createPullRequest(\"Fix\", ${ujson.write(token)})")
      assert(leak.failed)
      assert(leak.stderr.contains("CONTENT_DENIED"))
    }
  }
