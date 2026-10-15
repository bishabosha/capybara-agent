//> using file Interface.scala
//> using repository ivy2local
//> using dep "dev.capybara.skills::coding-api:0.1.0-SNAPSHOT"
package githubskill.internal

import scala.language.experimental.captureChecking
import java.net.{URI, Proxy, ProxySelector, SocketAddress}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import scala.util.control.NonFatal

/** Small private JSON codec keeps implementation libraries out of the public REPL API. */
private object Wire:
  enum Value:
    case Obj(fields: Map[String, Value])
    case Arr(elements: Vector[Value])
    case Str(value: String)
    case Num(value: BigDecimal)
    case Bool(value: Boolean)
    case Null
    def apply(key: String): Value = this match
      case Obj(fields) => fields.getOrElse(key, invalid())
      case _ => invalid()
    def get(key: String): Option[Value] = this match
      case Obj(fields) => fields.get(key)
      case _ => invalid()
    def text: String = this match
      case Str(value) => value
      case _ => invalid()
    def integer: Int = this match
      case Num(value) if value.isValidInt => value.toInt
      case _ => invalid()
    def items: Vector[Value] = this match
      case Arr(items) => items
      case _ => invalid()
  import Value.*
  def invalid(): Nothing = throw IllegalStateException("INVALID_RESPONSE")
  def obj(fields: (String, Value)*): Value = Obj(fields.toMap)
  def string(value: String): Value = Str(value)
  def number(value: Int): Value = Num(BigDecimal(value))
  def render(value: Value): String = value match
    case Obj(fields) => fields.toSeq.sortBy(_._1).map((key, value) => render(Str(key)) + ":" + render(value)).mkString("{", ",", "}")
    case Arr(items) => items.map(render).mkString("[", ",", "]")
    case Str(text) => "\"" + text.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""
    case Num(value) => value.toString
    case Bool(value) => value.toString
    case Null => "null"
  def parse(source: String): Value =
    var at = 0
    def space(): Unit = while at < source.length && " \r\n\t".contains(source(at)) do at += 1
    def take(c: Char): Unit =
      space()
      if at >= source.length || source(at) != c then invalid()
      at += 1
    def text(): String =
      take('"')
      val result = new StringBuilder
      var done = false
      while !done && at < source.length do
        val c = source(at)
        at += 1
        if c == '"' then done = true
        else if c == '\\' then
          if at >= source.length then invalid()
          val escaped = source(at)
          at += 1
          escaped match
            case '"' | '\\' | '/' => result.append(escaped)
            case 'b' => result.append('\b')
            case 'f' => result.append('\f')
            case 'n' => result.append('\n')
            case 'r' => result.append('\r')
            case 't' => result.append('\t')
            case 'u' =>
              if at + 4 > source.length || !source.substring(at, at + 4).matches("[0-9a-fA-F]{4}") then invalid()
              result.append(Integer.parseInt(source.substring(at, at + 4), 16).toChar)
              at += 4
            case _ => invalid()
        else if c < ' ' then invalid()
        else result.append(c)
      if !done then invalid()
      result.toString
    def value(depth: Int): Value =
      space()
      if depth > 64 || at >= source.length then invalid()
      source(at) match
        case '"' => Str(text())
        case '{' =>
          take('{')
          space()
          var fields = Map.empty[String, Value]
          if at < source.length && source(at) != '}' then
            var more = true
            while more do
              val key = text()
              if fields.contains(key) then invalid()
              take(':')
              fields += key -> value(depth + 1)
              space()
              more = at < source.length && source(at) == ','
              if more then at += 1
          take('}')
          Obj(fields)
        case '[' =>
          take('[')
          space()
          val items = Vector.newBuilder[Value]
          if at < source.length && source(at) != ']' then
            var more = true
            while more do
              items += value(depth + 1)
              space()
              more = at < source.length && source(at) == ','
              if more then at += 1
          take(']')
          Arr(items.result())
        case _ =>
          val token = source.substring(at).takeWhile(c => !" \r\n\t,]}".contains(c))
          at += token.length
          token match
            case "true" => Bool(true)
            case "false" => Bool(false)
            case "null" => Null
            case n if n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") => Num(BigDecimal(n))
            case _ => invalid()
    val result = value(0)
    space()
    if at != source.length then invalid()
    result

private class GitHubImpl(repository: String, base: String, token: String, endpoint: String) extends githubskill.GitHub:
  import Wire.*
  import Wire.Value.*
  private class PolicyError(code: String) extends IllegalStateException(code)
  private def requirePolicy(ok: Boolean, code: String): Unit = if !ok then throw PolicyError(code)
  requirePolicy(repository.matches("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*"), "INVALID_REPOSITORY")
  requirePolicy(base.matches("[A-Za-z0-9][A-Za-z0-9_./-]*") && !base.contains(".."), "INVALID_BRANCH")
  requirePolicy(token.length >= 16 && token.length <= 4096 && !token.exists(_.isWhitespace), "AUTH_FAILED")
  private val forbidden = Vector(token, Base64.getEncoder.encodeToString(token.getBytes(UTF_8)),
    token.getBytes(UTF_8).iterator.map(b => f"${b & 0xff}%02x").mkString)
  private val secret = "(?s).*(LAB_ONLY_CANARY_|github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]{16,}|-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----|sk-[A-Za-z0-9_-]{16,}).*".r
  private def publicText(text: String, limit: Int = 1048576): String =
    requirePolicy(text.getBytes(UTF_8).length <= limit && !forbidden.exists(text.contains) &&
      !secret.matches(text) && !text.exists(c => c < ' ' && !"\n\r\t".contains(c)), "CONTENT_DENIED")
    text
  private val noProxy = new ProxySelector:
    def select(uri: URI): java.util.List[Proxy] = java.util.List.of(Proxy.NO_PROXY)
    def connectFailed(uri: URI, address: SocketAddress, error: java.io.IOException): Unit = ()
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NEVER).proxy(noProxy).build()
  private def request(method: String, suffix: String, body: Value = Null): Value =
    val payload = if body == Null then "" else publicText(render(body), 2097152)
    val publisher = if body == Null then HttpRequest.BodyPublishers.noBody()
      else HttpRequest.BodyPublishers.ofString(payload, UTF_8)
    val req = HttpRequest.newBuilder(URI.create(endpoint + "/repos/" + repository + suffix))
      .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
      .header("Accept", "application/vnd.github+json").header("Content-Type", "application/json")
      .header("X-GitHub-Api-Version", "2026-03-10").header("User-Agent", "capybara-agent")
      .method(method, publisher).build()
    try
      val response = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
      val input = response.body()
      try
        requirePolicy(Set(200, 201)(response.statusCode()), response.statusCode() match
          case 401 => "AUTH_FAILED"
          case 404 => "NOT_FOUND"
          case 409 | 422 => "CONFLICT"
          case 429 => "RATE_LIMITED"
          case _ => "GITHUB_REQUEST_REJECTED")
        val bytes = input.readNBytes(8388609)
        requirePolicy(bytes.length <= 8388608, "INVALID_RESPONSE")
        parse(new String(bytes, UTF_8))
      finally input.close()
    catch
      case error: PolicyError => throw error
      case error: IllegalStateException if error.getMessage == "INVALID_RESPONSE" => throw error
      case NonFatal(_) => throw PolicyError("GITHUB_UNAVAILABLE")
  private def validSha(value: String): String =
    requirePolicy(value.matches("[0-9a-f]{40}"), "INVALID_RESPONSE")
    value
  private def topic(name: String): String =
    requirePolicy(name.startsWith("capybara/") && name != base &&
      name.matches("[A-Za-z0-9][A-Za-z0-9_/-]*") && !name.contains("//"), "INVALID_BRANCH")
    name
  private def head(name: String): String = validSha(request("GET", "/git/ref/heads/" + name)("object")("sha").text)
  private def current(branch: githubskill.Branch): Unit =
    requirePolicy(head(topic(branch.name)) == validSha(branch.commit), "BRANCH_CHANGED")

  def lookupIssue(number: Int): githubskill.Issue =
    requirePolicy(number > 0, "INVALID_REQUEST")
    val item = request("GET", s"/issues/$number")
    requirePolicy(item("number").integer == number && item.get("pull_request").isEmpty, "INVALID_RESPONSE")
    val body = item.get("body").filter(_ != Null).map(_.text).getOrElse("")
    githubskill.Issue(number, publicText(item("title").text, 4096), publicText(body),
      s"https://github.com/$repository/issues/$number")

  def listIssues(page: Int): Seq[githubskill.IssueSummary] =
    requirePolicy(page > 0 && page <= 1000, "INVALID_REQUEST")
    val items = request("GET", s"/issues?state=open&per_page=25&page=$page").items
    requirePolicy(items.size <= 25, "INVALID_RESPONSE")
    items.filter(_.get("pull_request").isEmpty).map { item =>
      val n = item("number").integer
      requirePolicy(n > 0, "INVALID_RESPONSE")
      githubskill.IssueSummary(n, publicText(item("title").text, 4096), s"https://github.com/$repository/issues/$n")
    }

  def branch(name: String): githubskill.Branch = githubskill.Branch(topic(name), head(topic(name)))

  def createBranch(name: String): githubskill.Branch =
    topic(name)
    val commit = head(base)
    val _ = request("POST", "/git/refs", obj("ref" -> Str("refs/heads/" + name), "sha" -> Str(commit)))
    githubskill.Branch(name, commit)

  def commit(branch: githubskill.Branch, message: String, paths: Seq[String])
      (using files: editing.TextFiles): githubskill.Branch =
    publicText(message, 4096)
    requirePolicy(message.trim.nonEmpty && paths.nonEmpty && paths.size <= 64 && paths.distinct.size == paths.size, "INVALID_REQUEST")
    val changes = paths.map { path =>
      requirePolicy(!path.startsWith(".") && !path.split('/').exists(p => p.isEmpty || p == ".." || p.startsWith(".")), "PATH_DENIED")
      val content = publicText(files.read(path))
      obj("path" -> Str(path), "mode" -> Str("100644"), "type" -> Str("blob"), "content" -> Str(content))
    }
    // Validate every file and the full payload before any remote write.
    val patch = Arr(changes.toVector)
    publicText(render(patch), 2097152)
    current(branch)
    val baseTree = validSha(request("GET", "/git/commits/" + branch.commit)("tree")("sha").text)
    val tree = validSha(request("POST", "/git/trees", obj("base_tree" -> Str(baseTree), "tree" -> patch))("sha").text)
    val commit = validSha(request("POST", "/git/commits", obj("message" -> Str(message),
      "tree" -> Str(tree), "parents" -> Arr(Vector(Str(branch.commit)))))("sha").text)
    val _ = request("PATCH", "/git/refs/heads/" + branch.name, obj("sha" -> Str(commit), "force" -> Bool(false)))
    githubskill.Branch(branch.name, commit)

  def createPullRequest(branch: githubskill.Branch, title: String, body: String): githubskill.PullRequest =
    publicText(title, 256)
    publicText(body)
    requirePolicy(title.trim.nonEmpty, "INVALID_REQUEST")
    current(branch)
    val owner = repository.takeWhile(_ != '/')
    val existing = request("GET", s"/pulls?state=open&head=$owner:${branch.name}&base=$base&per_page=100").items
    val matching = existing.filter(item => item("head")("ref").text == branch.name &&
      item("head")("sha").text == branch.commit && item("base")("ref").text == base &&
      item("head")("repo")("full_name").text.equalsIgnoreCase(repository))
    requirePolicy(matching.size <= 1, "INVALID_RESPONSE")
    val item = matching.headOption.getOrElse(request("POST", "/pulls", obj("title" -> Str(title),
      "body" -> Str(body), "head" -> Str(branch.name), "base" -> Str(base),
      "draft" -> Bool(true), "maintainer_can_modify" -> Bool(false))))
    val number = item("number").integer
    requirePolicy(number > 0, "INVALID_RESPONSE")
    githubskill.PullRequest(number, s"https://github.com/$repository/pull/$number")

  def checks(branch: githubskill.Branch): githubskill.CheckSummary =
    current(branch)
    val statuses = request("GET", s"/commits/${branch.commit}/status?per_page=100")
    val checks = request("GET", s"/commits/${branch.commit}/check-runs?per_page=100")
    val runs = checks("check_runs").items
    requirePolicy(checks("total_count").integer == runs.size && statuses("total_count").integer <= 100, "INVALID_RESPONSE")
    val count = statuses("total_count").integer
    val failed = Set("failure", "error")(statuses("state").text) || runs.exists(r =>
      r.get("conclusion").filter(_ != Null).exists(c => Set("failure", "cancelled", "timed_out", "action_required")(c.text)))
    val passed = (runs.nonEmpty || count > 0) && (count == 0 || statuses("state").text == "success") &&
      runs.forall(r => r("head_sha").text == branch.commit && r("status").text == "completed" && r("conclusion").text == "success")
    githubskill.CheckSummary(branch.commit, if failed then "failed" else if passed then "passed" else "pending")

  override def toString: String = "GitHub(<capability>)"

@caps.rejectSafe
def impl(): githubskill.GitHub =
  val repository = sys.env.getOrElse("CAPYBARA_GITHUB_REPOSITORY", throw IllegalStateException("NOT_CONFIGURED"))
  val token = sys.env.getOrElse("CAPYBARA_GITHUB_TOKEN", throw IllegalStateException("AUTH_FAILED"))
  configured(repository, sys.env.getOrElse("CAPYBARA_GITHUB_BASE", "main"), token, "https://api.github.com")

/** Only trusted wiring can replace the endpoint, e.g. a deterministic test server. */
@caps.rejectSafe
def configured(repository: String, base: String, token: String, endpoint: String): githubskill.GitHub =
  new GitHubImpl(repository, base, token, endpoint)
