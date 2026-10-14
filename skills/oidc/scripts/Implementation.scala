//> using file Interface.scala
package oidc.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

import oidc.{Challenge, Response, Status}

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

// ---------------------------------------------------------------------------
// Login state across tool calls.
//
// The agent host reloads the skill jars in a fresh class loader on every tool
// call, so nothing in this object survives. The state of the one current
// login therefore lives in a JVM-global registry made of JDK types only:
// `System.getProperties` maps the key below to a ConcurrentHashMap[String,
// String]. Values never leave this file; agent code cannot refer to
// `java.lang.System` under safe mode.
// ---------------------------------------------------------------------------

private object Store {
  private val Key = "oidc.skill.login"

  type Fields = ConcurrentHashMap[String, String]

  /** The current login's fields, or None if no login was started. */
  def current(): Option[Fields] =
    System.getProperties.get(Key) match {
      case m: ConcurrentHashMap[?, ?] => Some(m.asInstanceOf[Fields])
      case _                          => None
    }

  /** Replace the current login. */
  def replace(fields: Map[String, String]): Fields = {
    val m = new Fields()
    fields.foreach { case (k, v) => m.put(k, v) }
    val _ = System.getProperties.put(Key, m)
    m
  }

  def clear(): Unit = { val _ = System.getProperties.remove(Key) }
}

/** The handle the agent sees: carries the login id (not a secret), no other members, not a Product,
  * masked toString. A handle from a previous login is stale and every call with it throws.
  */
final class Handle private[internal] (private[internal] val id: String) {
  override def toString: String = "Session(****)"
}

private object PrivateOidc extends oidc.Oidc {
  type Session = Handle

  private val http = new Http()
  private val Grant = "urn:ietf:params:oauth:grant-type:device_code"

  private def now(): Long = System.currentTimeMillis()

  def start(): Challenge = {
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
    val uri = json.str("verification_uri_complete").getOrElse(need("verification_uri"))
    val userCode = need("user_code")
    val expiresIn = json.num("expires_in").map(_.toInt).getOrElse(600)
    val intervalMs = json.num("interval").map(_.toLong).getOrElse(5L) * 1000L
    val _ = Store.replace(
      Map(
        "id" -> java.util.UUID.randomUUID().toString,
        "state" -> "pending",
        "device_code" -> need("device_code"),
        "client_id" -> config.clientId,
        "token_endpoint" -> ep.token.toString,
        "userinfo" -> ep.userinfo.map(_.toString).getOrElse(""),
        "api_base" -> config.apiBase,
        "interval_ms" -> intervalMs.toString,
        "next_poll_at" -> (now() + intervalMs).toString,
        "expires_at" -> (now() + expiresIn * 1000L).toString
      )
    )
    Challenge(uri, userCode, expiresIn)
  }

  def status(): Status = Store.current() match {
    case None    => Status.NoLogin
    case Some(f) =>
      f.get("state") match {
        case "ready"   => Status.Ready
        case "expired" => Status.Expired
        case "denied"  => Status.Denied
        case _         => poll(f)
      }
  }

  private def poll(f: Store.Fields): Status = {
    if (now() < f.get("next_poll_at").toLong) return Status.Pending
    if (now() > f.get("expires_at").toLong) { f.put("state", "expired"); return Status.Expired }
    val (ts, tb) = http.postForm(
      URI.create(f.get("token_endpoint")),
      Seq(
        "grant_type" -> Grant,
        "device_code" -> f.get("device_code"),
        "client_id" -> f.get("client_id")
      )
    )
    f.put("next_poll_at", (now() + f.get("interval_ms").toLong).toString)
    val tj = Json
      .parse(tb)
      .fold(e => throw new IllegalStateException(s"oidc: token endpoint: $e"), identity)
    if (ts == 200) {
      val access = tj
        .str("access_token")
        .getOrElse(throw new IllegalStateException("oidc: token response has no access_token"))
      f.put("access", access)
      tj.str("refresh_token").foreach(r => f.put("refresh", r))
      f.remove("device_code")
      f.put("state", "ready")
      Status.Ready
    } else
      tj.str("error") match {
        case Some("authorization_pending") => Status.Pending
        case Some("slow_down")             =>
          val iv = f.get("interval_ms").toLong + 5000L
          f.put("interval_ms", iv.toString); f.put("next_poll_at", (now() + iv).toString)
          Status.Pending
        case Some("expired_token") => f.put("state", "expired"); Status.Expired
        case Some("access_denied") => f.put("state", "denied"); Status.Denied
        case other                 =>
          throw new IllegalStateException(
            s"oidc: token endpoint: HTTP $ts, error=${other.getOrElse("?")}"
          )
      }
  }

  def session(): Option[Handle] =
    Store.current().filter(_.get("state") == "ready").map(f => new Handle(f.get("id")))

  /** The live fields for a handle, or throw if the handle is stale/closed. */
  private def live(s: Handle): Store.Fields =
    Store
      .current()
      .filter(f => f.get("id") == s.id && f.get("state") == "ready")
      .getOrElse(throw new IllegalStateException("oidc: session closed"))

  def get(s: Handle, path: String): Response = {
    val f = live(s)
    val base = URI.create(f.get("api_base"))
    val rel = RelativePath
      .validate(path)
      .fold(e => throw new IllegalArgumentException(s"oidc: $e"), identity)
    val target = URI.create(base.toString + rel)
    if (
      target.getScheme != base.getScheme || target.getHost != base.getHost || target.getPort != base.getPort
    )
      throw new IllegalArgumentException("oidc: path escapes the session's API host")
    val (st, body) = http.get(target, Some(f.get("access")))
    if (st == 401 && f.containsKey("refresh") && refresh(f)) {
      val (st2, body2) = http.get(target, Some(f.get("access"))); Response(st2, body2)
    } else Response(st, body)
  }

  def whoami(s: Handle): Response = {
    val f = live(s)
    val ui = Option(f.get("userinfo"))
      .filter(_.nonEmpty)
      .map(URI.create)
      .getOrElse(
        throw new IllegalStateException("oidc: the provider publishes no userinfo_endpoint")
      )
    val base = URI.create(f.get("api_base"))
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

  def logout(s: Handle): Unit =
    Store.current().foreach(f => if (f.get("id") == s.id) Store.clear())

  private def refresh(f: Store.Fields): Boolean = {
    val (st, body) = http.postForm(
      URI.create(f.get("token_endpoint")),
      Seq(
        "grant_type" -> "refresh_token",
        "refresh_token" -> f.get("refresh"),
        "client_id" -> f.get("client_id")
      )
    )
    if (st != 200) false
    else
      Json.parse(body).toOption.flatMap(_.str("access_token")) match {
        case Some(a) =>
          f.put("access", a)
          Json
            .parse(body)
            .toOption
            .flatMap(_.str("refresh_token"))
            .foreach(r => f.put("refresh", r))
          true
        case None => false
      }
  }
}

@assumeSafe
val impl: oidc.Oidc = PrivateOidc
