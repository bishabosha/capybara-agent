package capybara.agent

import steps.result.Result
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

/** Real Scala HTTP transport and safe skill composition, with a deterministic local GitHub server. */
class GitHubSkillSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private val token = "SYNTHETIC_HOST_TOKEN_abcdefghijklmnopqrstuvwxyz"
  private val initial = "1" * 40
  private val tree = "2" * 40
  private val committed = "3" * 40
  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(value) => value
    case Result.Err(error) => fail(error)
  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  private class Remote extends AutoCloseable:
    val refs = scala.collection.mutable.Map("main" -> initial)
    val requests = scala.collection.mutable.ArrayBuffer.empty[(String, String, ujson.Value)]
    val pulls = scala.collection.mutable.ArrayBuffer.empty[ujson.Value]
    var secretIssue = false
    var successChecks = false
    var rejectRefCreation = false
    var baseConflict = false
    var rejectPullCreation = false
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/repos/example/lab", exchange => {
      try
        assertEquals(exchange.getRequestHeaders.getFirst("Authorization"), "Bearer " + token)
        val method = exchange.getRequestMethod
        val path = exchange.getRequestURI.getPath.stripPrefix("/repos/example/lab")
        val raw = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
        val body = if raw.isEmpty then ujson.Null else ujson.read(raw)
        requests.synchronized { val _ = requests += ((method, path, body)) }
        val issue = ujson.Obj("number" -> 1, "title" -> "Fix whitespace λ", "body" ->
          (if secretIssue then token else "Handle empty input, repeated spaces and \\t."))
        val (status, result) = (method, path) match
          case ("GET", "/issues") => (200, ujson.Arr(issue))
          case ("GET", "/issues/1") => (200, issue)
          case ("GET", p) if p.startsWith("/git/ref/heads/") =>
            (if baseConflict && p == "/git/ref/heads/main" then None else refs.get(p.stripPrefix("/git/ref/heads/"))) match
              case Some(sha) => (200, ujson.Obj("object" -> ujson.Obj("sha" -> sha)))
              case None => (if baseConflict && p == "/git/ref/heads/main" then 409 else 404,
                ujson.Obj("message" -> token))
          case ("POST", "/git/refs") =>
            val name = body("ref").str.stripPrefix("refs/heads/")
            if rejectRefCreation || refs.contains(name) then (422, ujson.Obj("message" -> token))
            else
              refs(name) = body("sha").str
              (201, ujson.Obj("object" -> ujson.Obj("sha" -> body("sha"))))
          case ("GET", p) if p.startsWith("/git/commits/") =>
            (200, ujson.Obj("tree" -> ujson.Obj("sha" -> tree)))
          case ("POST", "/git/trees") => (201, ujson.Obj("sha" -> tree))
          case ("POST", "/git/commits") => (201, ujson.Obj("sha" -> committed))
          case ("PATCH", p) if p.startsWith("/git/refs/heads/") =>
            assert(!body("force").bool)
            refs(p.stripPrefix("/git/refs/heads/")) = body("sha").str
            (200, ujson.Obj("object" -> ujson.Obj("sha" -> body("sha"))))
          case ("GET", "/pulls") => (200, ujson.Arr.from(pulls))
          case ("POST", "/pulls") if rejectPullCreation => (422, ujson.Obj("message" -> token))
          case ("POST", "/pulls") =>
            assert(body("draft").bool)
            val item = ujson.Obj("number" -> 2, "draft" -> true,
              "head" -> ujson.Obj("ref" -> body("head"), "sha" -> committed,
                "repo" -> ujson.Obj("full_name" -> "example/lab")),
              "base" -> ujson.Obj("ref" -> "main"))
            pulls += item
            (201, item)
          case ("GET", p) if p.endsWith("/status") =>
            (200, ujson.Obj("state" -> "pending", "total_count" -> 0))
          case ("GET", p) if p.endsWith("/check-runs") =>
            val runs = if successChecks then Seq(ujson.Obj("head_sha" -> committed,
              "status" -> "completed", "conclusion" -> "success")) else Nil
            (200, ujson.Obj("total_count" -> runs.size, "check_runs" -> ujson.Arr.from(runs)))
          case _ => (500, ujson.Obj("message" -> token))
        val data = ujson.write(result).getBytes(UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, data.length.toLong)
        exchange.getResponseBody.write(data)
      finally exchange.close()
    })
    server.start()
    val endpoint = s"http://127.0.0.1:${server.getAddress.getPort}"
    def close(): Unit = server.stop(0)

  private def withSkills(test: (Seq[Skill], os.Path, Remote) => Unit): Unit =
    val root = os.temp.dir(prefix = "capybara-github-composed-")
    val remote = new Remote
    try
      os.makeDir.all(root / "src")
      os.write(root / "src/word-count.mjs", "export function countWords(text) {\n  return text.split(' ').length;\n}\n")
      val coding = loaded("skills/coding")
      val local = coding.copy(bindings = coding.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = s"editing.internal.configured(${ujson.write(root.toString)})")))
      val github = loaded("skills/github")
      val factory = s"githubskill.internal.configured(\"example/lab\", \"main\", ${ujson.write(token)}, ${ujson.write(remote.endpoint)})"
      val online = github.copy(bindings = github.bindings.map(b => (name = b.name, tpe = b.tpe, value = factory)))
      assertEquals(online.requires, Seq("coding"))
      test(Seq(online, local), root, remote)
    finally
      remote.close()
      os.remove.all(root)

  private def run(skills: Seq[Skill], code: String): ReplExec.CodeOutput =
    ReplExec.runCode(builtins, skills, code, CancellationToken.Never) match
      case Result.Ok(value) =>
        assert(!value.stdout.contains(token))
        assert(!value.stderr.contains(token))
        value
      case Result.Err(error) => fail(error)

  test("composed coding and GitHub skills implement, commit, push and create a draft PR without a process runner") {
    withSkills { (skills, root, remote) =>
      val output = SkillRunFixture.run(skills, os.pwd / "examples/snippets/github-lab.scala")
      assert(!output.failed, output.stderr)
      assert(os.read(root / "src/word-count.mjs").contains("clean.split(/\\s+/u)"))
      val values = output.stdout.linesIterator.map(ujson.read(_)("special.success")).toVector
      assertEquals(values(0)("title").str, "Fix whitespace λ")
      assertEquals(values(2)("url").str, "https://github.com/example/lab/pull/2")
      assertEquals(values(3)("status").str, "pending")
      assertEquals(remote.pulls.size, 1)
      if os.exists(os.pwd / ".agent-runtime/validate-presentation") then
        os.write.over(os.pwd / "presentations/capybara-architecture/public/assets/github-demo.txt", output.stdout)
      assert(!output.stdout.contains(token))
      assertEquals(remote.refs("main"), initial)
      val patch = remote.requests.find(_._2 == "/git/trees").get._3
      assertEquals(patch("tree")(0)("path").str, "src/word-count.mjs")
      assert(!ujson.write(patch).contains(token))
      val retry = run(skills, "puts(github.createPullRequest(github.branch(\"capybara/issue-1\"), \"Fix\", \"Fixes #1\"))")
      assert(!retry.failed, retry.stderr)
      assertEquals(remote.pulls.size, 1)
      remote.successChecks = true
      val checked = run(skills, "puts(github.checks(github.branch(\"capybara/issue-1\")))")
      assert(!checked.failed, checked.stderr)
      assertEquals(ujson.read(checked.stdout)("special.success")("status").str, "passed")
    }
  }

  test("ordinary demo resumes an interrupted run only while the topic still equals the base") {
    withSkills { (skills, root, remote) =>
      assert(!run(skills, "github.createBranch(\"capybara/issue-1\")").failed)
      val output = SkillRunFixture.run(skills, os.pwd / "examples/snippets/github-lab.scala")
      assertEquals(output.stderr, "")
      assert(os.read(root / "src/word-count.mjs").contains("clean.split"))
      assertEquals(remote.refs("main"), initial)
      assertEquals(remote.refs("capybara/issue-1"), committed)
      assertEquals(remote.pulls.size, 1)
      val writes = remote.requests.count(_._1 != "GET")
      val advanced = run(skills, "github.createBranch(\"capybara/issue-1\")")
      assert(advanced.failed && advanced.stderr.contains("BRANCH_ALREADY_EXISTS"), advanced.stderr)
      assertEquals(remote.refs("capybara/issue-1"), committed)
      assertEquals(remote.requests.count(_._1 != "GET"), writes + 1) // one rejected create, no ref update
    }
  }

  test("HTTP failures identify the operation without returning GitHub error bodies") {
    withSkills { (skills, _, remote) =>
      remote.baseConflict = true
      val base = run(skills, "github.createBranch(\"capybara/issue-1\")")
      assert(base.failed && base.stderr.contains("READ_BASE_BRANCH: CONFLICT (HTTP 409)"), base.stderr)
      remote.baseConflict = false
      remote.rejectRefCreation = true
      val invalid = run(skills, "github.createBranch(\"capybara/issue-1\")")
      assert(invalid.failed && invalid.stderr.contains("CREATE_BRANCH: VALIDATION_FAILED (HTTP 422)"), invalid.stderr)
      assert(!remote.refs.contains("capybara/issue-1"))
      remote.rejectRefCreation = false
      assert(!run(skills, "github.createBranch(\"capybara/issue-1\")").failed)
      remote.rejectPullCreation = true
      val pull = run(skills, "github.createPullRequest(github.branch(\"capybara/issue-1\"), \"Fix\", \"Fixes #1\")")
      assert(pull.failed && pull.stderr.contains("CREATE_PULL_REQUEST: VALIDATION_FAILED (HTTP 422)"), pull.stderr)
    }
  }

  test("capture checking rejects cross-skill effects and credentials have no public access path") {
    withSkills { (skills, _, _) =>
      assert(!run(skills, "puts(1)").failed)
      for code <- Seq(
        os.read(os.pwd / "examples/snippets/github-hidden-files.scala"),
        "coding.edit(\"src/word-count.mjs\")(text => { puts(text); text })",
        "coding.edit(\"src/word-count.mjs\")(text => github.lookupIssue(1).body)",
        "puts(github.token)", "github.test()", "githubskill.internal.impl()",
        "puts(sys.env(\"CAPYBARA_GITHUB_TOKEN\"))",
        "puts(github.getClass.getDeclaredFields.toList)",
        "new java.lang.ProcessBuilder(\"env\").start()"
      ) do assert(ReplExec.runCode(builtins, skills, code, CancellationToken.Never).isInstanceOf[Result.Err[?]], code)
    }
  }

  test("GitHub file access requires TextFiles in its public safe method context") {
    withSkills { (skills, root, remote) =>
      // Selecting GitHub alone resolves the manifest's required context.
      assert(!run(Seq(skills.head), "val files = summon[editing.TextFiles]; puts(1)").failed)
      // Keep the API dependency, but remove the automatic runtime file grant.
      val githubOnly = Seq(skills.head.copy(requires = Nil))
      assert(!run(githubOnly, "puts(github.lookupIssue(1))").failed)
      val rejected = ReplExec.runCode(builtins, githubOnly,
        "github.commit(githubskill.Branch(\"capybara/issue-1\", \"" + initial + "\"), \"Fix\", Seq(\"src/word-count.mjs\"))",
        CancellationToken.Never)
      rejected match
        case Result.Err(error) => assert(error.contains("TextFiles"), error)
        case Result.Ok(value) => fail("Commit compiled without file authority: " + value)
      assertEquals(remote.requests.count(_._1 != "GET"), 0)
      assert(os.read(root / "src/word-count.mjs").contains("text.split"))
    }
  }

  test("secret-bearing issues, files and PR metadata are rejected without returning credentials") {
    withSkills { (skills, root, remote) =>
      remote.secretIssue = true
      val issue = run(skills, "github.lookupIssue(1)")
      assert(issue.failed && issue.stderr.contains("CONTENT_DENIED"))
      remote.secretIssue = false
      val create = run(skills, "puts(github.createBranch(\"capybara/issue-1\"))")
      assert(!create.failed, create.stderr)
      os.write(root / "leak.txt", token)
      val before = remote.requests.count(_._1 != "GET")
      val leak = run(skills, "github.commit(github.branch(\"capybara/issue-1\"), \"Fix\", Seq(\"leak.txt\"))")
      assert(leak.failed && leak.stderr.contains("CONTENT_DENIED"))
      assertEquals(remote.requests.count(_._1 != "GET"), before)
      val metadata = run(skills, s"github.createPullRequest(github.branch(\"capybara/issue-1\"), \"Fix\", ${ujson.write(token)})")
      assert(metadata.failed && metadata.stderr.contains("CONTENT_DENIED"))
      assertEquals(remote.requests.count(_._1 != "GET"), before)
      val denied = run(skills, "github.createBranch(\"main\")")
      assert(denied.failed && denied.stderr.contains("INVALID_BRANCH"))
      val error = run(skills, "github.branch(\"capybara/missing\")")
      assert(error.failed && error.stderr.contains("NOT_FOUND"))
    }
  }
