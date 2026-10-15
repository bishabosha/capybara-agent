//> using file Interface.scala
package oidc.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

import oidc.Response

// ---------------------------------------------------------------------------
// Configuration: environment only.
// ---------------------------------------------------------------------------

private final case class Config(issuer: String, clientId: String, scope: String, apiBase: String)

private object Config {
  def fromEnv(): Config = {
    def required(name: String): String =
      Option(System.getenv(name))
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(throw new IllegalStateException(s"oidc: environment variable $name is not set"))
    def optional(name: String, default: String): String =
      Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(default)
    val issuer = required("OIDC_ISSUER").stripSuffix("/")
    Config(
      issuer = issuer,
      clientId = required("OIDC_CLIENT_ID"),
      scope = optional("OIDC_SCOPE", "openid profile email"),
      apiBase = optional("OIDC_API_BASE", issuer).stripSuffix("/")
    )
  }
}

// ---------------------------------------------------------------------------
// JDK HTTP client. Redirects are NEVER followed, so a bearer cannot be
// forwarded to another host by a 3xx. Nothing here logs headers or bodies.
// ---------------------------------------------------------------------------

private final class Http(timeout: Duration = Duration.ofSeconds(15)) {
  private val client: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(timeout)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  def get(uri: URI, bearer: Option[String]): (Int, String) = {
    val b = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET()
    bearer.foreach(t => b.header("Authorization", "Bearer " + t))
    send(b.build())
  }

  def postForm(uri: URI, form: Seq[(String, String)]): (Int, String) = {
    val body = form.map((k, v) => enc(k) + "=" + enc(v)).mkString("&")
    send(
      HttpRequest
        .newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    )
  }

  private def send(req: HttpRequest): (Int, String) = {
    val res = client.send(req, HttpResponse.BodyHandlers.ofString(UTF_8))
    (res.statusCode(), res.body())
  }

  private def enc(s: String): String = URLEncoder.encode(s, UTF_8)
}

// ---------------------------------------------------------------------------
// Minimal JSON reader (no JSON library on the skill classpath).
// ---------------------------------------------------------------------------

private enum Json {
  case Obj(fields: Map[String, Json])
  case Arr(items: List[Json])
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null

  def field(name: String): Option[Json] = this match {
    case Obj(fs) => fs.get(name)
    case _       => None
  }
  def str(name: String): Option[String] = field(name).collect { case Str(s) => s }
  def num(name: String): Option[BigDecimal] = field(name).collect { case Num(n) => n }
}

private object Json {
  private final case class JsonError(msg: String) extends RuntimeException(msg)

  def parse(text: String): Either[String, Json] = {
    var i = 0
    def fail(msg: String): Nothing = throw JsonError(s"$msg at offset $i")
    def ws(): Unit = while (i < text.length && text.charAt(i).isWhitespace) i += 1
    def expect(c: Char): Unit = {
      ws(); if (i < text.length && text.charAt(i) == c) i += 1 else fail(s"expected '$c'")
    }
    def literal(word: String, value: Json): Json =
      if (text.startsWith(word, i)) { i += word.length; value }
      else fail("bad literal")
    def string(): String = {
      expect('"')
      val sb = new StringBuilder
      while (i < text.length && text.charAt(i) != '"') {
        val c = text.charAt(i)
        if (c == '\\') {
          i += 1
          if (i >= text.length) fail("bad escape")
          text.charAt(i) match {
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case '/'  => sb += '/'
            case 'b'  => sb += '\b'
            case 'f'  => sb += '\f'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  =>
              if (i + 4 >= text.length) fail("bad \\u escape")
              sb += Integer.parseInt(text.substring(i + 1, i + 5), 16).toChar
              i += 4
            case _ => fail("bad escape")
          }
          i += 1
        } else { sb += c; i += 1 }
      }
      if (i >= text.length) fail("unterminated string")
      i += 1
      sb.result()
    }
    def number(): Json = {
      val start = i
      while (i < text.length && "+-0123456789.eE".indexOf(text.charAt(i).toInt) >= 0) i += 1
      try Num(BigDecimal(text.substring(start, i)))
      catch { case _: NumberFormatException => fail("bad number") }
    }
    def value(): Json = {
      ws()
      if (i >= text.length) fail("unexpected end")
      text.charAt(i) match {
        case '{' =>
          i += 1; ws()
          val b = Map.newBuilder[String, Json]
          if (i < text.length && text.charAt(i) == '}') i += 1
          else {
            var more = true
            while (more) {
              val k = string(); expect(':'); b += k -> value(); ws()
              if (i < text.length && text.charAt(i) == ',') i += 1
              else { expect('}'); more = false }
            }
          }
          Obj(b.result())
        case '[' =>
          i += 1; ws()
          val b = List.newBuilder[Json]
          if (i < text.length && text.charAt(i) == ']') i += 1
          else {
            var more = true
            while (more) {
              b += value(); ws()
              if (i < text.length && text.charAt(i) == ',') i += 1
              else { expect(']'); more = false }
            }
          }
          Arr(b.result())
        case '"' => Str(string())
        case 't' => literal("true", Bool(true))
        case 'f' => literal("false", Bool(false))
        case 'n' => literal("null", Null)
        case _   => number()
      }
    }
    try {
      val v = value(); ws(); if (i != text.length) Left(s"trailing data at offset $i") else Right(v)
    } catch { case JsonError(msg) => Left(msg) }
  }
}

// ---------------------------------------------------------------------------
// OpenID discovery and the device authorization grant (RFC 8628).
// ---------------------------------------------------------------------------

private final case class Endpoints(deviceAuthorization: URI, token: URI, userinfo: Option[URI])

private object Endpoints {
  def discover(http: Http, issuer: String): Endpoints = {
    val (status, body) = http.get(URI.create(issuer + "/.well-known/openid-configuration"), None)
    if (status != 200) throw new IllegalStateException(s"oidc: discovery failed with HTTP $status")
    val json =
      Json.parse(body).fold(e => throw new IllegalStateException(s"oidc: discovery: $e"), identity)
    def need(name: String) =
      json.str(name).getOrElse(throw new IllegalStateException(s"oidc: discovery has no $name"))
    Endpoints(
      URI.create(need("device_authorization_endpoint")),
      URI.create(need("token_endpoint")),
      json.str("userinfo_endpoint").map(URI.create)
    )
  }
}

/** Access + refresh token. Never printed. */
private final class Tokens(val access: String, val refresh: Option[String]) {
  override def toString: String = "Tokens(****)"
}

/** The one path validator: a session may only be used against its own API base. */
private object RelativePath {
  def validate(path: String): Either[String, String] = {
    val pathPart = path.takeWhile(ch => ch != '?' && ch != '#')
    def isDot(seg: String) = { val s = seg.toLowerCase.replace("%2e", "."); s == ".." || s == "." }
    if (path.isEmpty) Left("path must not be empty")
    else if (path.exists(ch => ch < ' ' || ch == '\u007f'))
      Left("path must not contain control characters")
    else if (path.contains("://")) Left("path must be relative: no scheme (://)")
    else if (!path.startsWith("/")) Left("path must start with '/'")
    else if (path.startsWith("//")) Left("path must be relative: no authority (//)")
    else if (path.contains("\\")) Left("path must not contain '\\'")
    else if (pathPart.split("/").toList.exists(isDot))
      Left("path must not contain '.' or '..' segments")
    else Right(path)
  }
}

/** The handle the agent sees: no members, not a Product, masked toString. */
final class Handle private[internal] (
    private[internal] val apiBase: URI,
    private[internal] val tokenEndpoint: URI,
    private[internal] val userinfo: Option[URI],
    initial: Tokens
) {
  private[internal] val tokens: AtomicReference[Option[Tokens]] = new AtomicReference(Some(initial))
  override def toString: String = "Session(****)"
}

private object PrivateOidc extends oidc.Oidc {
  type Session = Handle

  private val http = new Http()
  private val Grant = "urn:ietf:params:oauth:grant-type:device_code"

  def login(): Handle = {
    val config = Config.fromEnv()
    val ep = Endpoints.discover(http, config.issuer)
    val (st, body) = http.postForm(
      ep.deviceAuthorization,
      Seq("client_id" -> config.clientId, "scope" -> config.scope)
    )
    if (st != 200)
      throw new IllegalStateException(s"oidc: device authorization failed with HTTP $st")
    val json = Json
      .parse(body)
      .fold(e => throw new IllegalStateException(s"oidc: device authorization: $e"), identity)
    def need(name: String) = json
      .str(name)
      .getOrElse(throw new IllegalStateException(s"oidc: device authorization has no $name"))
    val deviceCode = need("device_code")
    val userCode = need("user_code")
    val uri = json.str("verification_uri_complete").getOrElse(need("verification_uri"))
    val expiresIn = json.num("expires_in").map(_.toInt).getOrElse(600)
    var interval = json.num("interval").map(_.toLong).getOrElse(5L) * 1000L
    val deadline = System.nanoTime() + expiresIn * 1000000000L

    // Public information, for the human at the terminal — never a token.
    System.err.println(
      s"oidc: open $uri in a browser and confirm code $userCode (expires in ${expiresIn}s)"
    )
    System.err.flush()

    while (true) {
      Thread.sleep(interval)
      if (System.nanoTime() > deadline)
        throw new IllegalStateException("oidc: login expired before the user approved")
      val (ts, tb) = http.postForm(
        ep.token,
        Seq("grant_type" -> Grant, "device_code" -> deviceCode, "client_id" -> config.clientId)
      )
      val tj = Json
        .parse(tb)
        .fold(e => throw new IllegalStateException(s"oidc: token endpoint: $e"), identity)
      if (ts == 200) {
        val access = tj
          .str("access_token")
          .getOrElse(throw new IllegalStateException("oidc: token response has no access_token"))
        return new Handle(
          URI.create(config.apiBase),
          ep.token,
          ep.userinfo,
          new Tokens(access, tj.str("refresh_token"))
        )
      }
      tj.str("error") match {
        case Some("authorization_pending") => ()
        case Some("slow_down")             => interval += 5000L
        case Some("expired_token")         =>
          throw new IllegalStateException("oidc: login expired before the user approved")
        case Some("access_denied") =>
          throw new IllegalStateException("oidc: the user denied the login")
        case other =>
          throw new IllegalStateException(
            s"oidc: token endpoint: HTTP $ts, error=${other.getOrElse("?")}"
          )
      }
    }
    throw new IllegalStateException("unreachable")
  }

  def get(s: Handle, path: String): Response = {
    val rel = RelativePath
      .validate(path)
      .fold(e => throw new IllegalArgumentException(s"oidc: $e"), identity)
    val target = URI.create(s.apiBase.toString + rel)
    if (
      target.getScheme != s.apiBase.getScheme || target.getHost != s.apiBase.getHost || target.getPort != s.apiBase.getPort
    )
      throw new IllegalArgumentException("oidc: path escapes the session's API host")
    val tokens = s.tokens.get().getOrElse(throw new IllegalStateException("oidc: session closed"))
    val (st, body) = http.get(target, Some(tokens.access))
    if (st == 401 && tokens.refresh.isDefined) {
      refresh(s, tokens) match {
        case Some(fresh) =>
          val (st2, body2) = http.get(target, Some(fresh.access)); Response(st2, body2)
        case None => Response(st, body)
      }
    } else Response(st, body)
  }

  def whoami(s: Handle): Response = {
    val ui = s.userinfo.getOrElse(
      throw new IllegalStateException("oidc: the provider publishes no userinfo_endpoint")
    )
    val base = s.apiBase
    val basePath = base.getRawPath.stripSuffix("/")
    if (
      ui.getScheme != base.getScheme || ui.getHost != base.getHost || ui.getPort != base.getPort
      || !ui.getRawPath.startsWith(basePath + "/")
    )
      throw new IllegalStateException(
        "oidc: userinfo_endpoint is not under the session's API base; call get() on the API instead"
      )
    get(s, ui.getRawPath.drop(basePath.length))
  }

  def logout(s: Handle): Unit = s.tokens.set(None)

  private def refresh(s: Handle, old: Tokens): Option[Tokens] =
    old.refresh.flatMap { rt =>
      val (st, body) = http.postForm(
        s.tokenEndpoint,
        Seq(
          "grant_type" -> "refresh_token",
          "refresh_token" -> rt,
          "client_id" -> Config.fromEnv().clientId
        )
      )
      if (st != 200) None
      else
        Json.parse(body).toOption.flatMap(_.str("access_token")).map { a =>
          val merged = new Tokens(
            a,
            Json.parse(body).toOption.flatMap(_.str("refresh_token")).orElse(old.refresh)
          )
          s.tokens.set(Some(merged)); merged
        }
    }
}

@assumeSafe
val impl: oidc.Oidc = PrivateOidc
