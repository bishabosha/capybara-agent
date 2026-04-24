//> using scala "3.4.2"
//> using dep "com.lihaoyi::ujson:4.4.3"

/* Based on code from https://github.com/badlogic/pi-mono, for personal use only! */

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.awt.Desktop
import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.net.{BindException, InetSocketAddress, URI, URLEncoder, URLDecoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}
import scala.collection.mutable
import scala.util.Try

case class Credentials(access: String, refresh: String, expires: Long, accountId: String)

object OpenAICodexOAuthDemo:
  private val CallbackHost = sys.env.getOrElse("PI_OAUTH_CALLBACK_HOST", "127.0.0.1")
  private val CallbackPort = 1455
  private val RedirectUri = s"http://localhost:$CallbackPort/auth/callback"
  private val ClientId = "app_EMoamEEZ73f0CkXaXp7hrann"
  private val AuthorizeUrl = "https://auth.openai.com/oauth/authorize"
  private val TokenUrl = "https://auth.openai.com/oauth/token"
  private val ClaimPath = "https://api.openai.com/auth"
  private val Scope = "openid profile email offline_access"
  private val CodexUrl = "https://chatgpt.com/backend-api/codex/responses"
  private val DefaultModel = "gpt-5.4-mini"
  private val DefaultAuthFile = Paths.get(sys.props("user.home"), ".openai-codex-scala-cli.json")
  private val DefaultToolTimeoutSeconds =
    sys.env.get("RUN_SCALA_CODE_TIMEOUT_SECONDS").flatMap(value => Try(value.toLong).toOption).getOrElse(60L)
  private val SuccessHtml =
    "<html><body><h1>Authentication complete</h1><p>You can close this window and return to the terminal.</p></body></html>"
  private val ErrorHtmlPrefix =
    "<html><body><h1>Authentication error</h1><p>"
  private val ErrorHtmlSuffix = "</p></body></html>"

  private val httpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  def proc(rawArgs: List[String]): Unit =
    val args = parseArgs(rawArgs)
    if args.help then
      printHelp()
      return

    val prompt =
      args.prompt
        .orElse(if args.positionals.nonEmpty then Some(args.positionals.mkString(" ")) else None)
        .getOrElse {
          System.err.println("Missing prompt. Pass --prompt '...' or provide trailing arguments.")
          printHelp()
          sys.exit(1)
        }

    val creds = loadOrLogin(args.authFile, args.forceLogin)
    println(s"Using account ${creds.accountId} with model ${args.model}")
    println()
    streamResponse(creds, args.model, prompt, args.reasoningEffort)

  private case class Args(
      prompt: Option[String],
      model: String,
      authFile: Path,
      reasoningEffort: String,
      forceLogin: Boolean,
      help: Boolean,
      positionals: List[String]
  )

  private def parseArgs(args: List[String]): Args =
    var prompt: Option[String] = None
    var model = DefaultModel
    var authFile = DefaultAuthFile
    var reasoningEffort = "low"
    var forceLogin = false
    var help = false
    val positionals = mutable.ListBuffer.empty[String]

    var remaining = args
    while remaining.nonEmpty do
      remaining match
        case "--prompt" :: value :: tail =>
          prompt = Some(value)
          remaining = tail
        case "--model" :: value :: tail =>
          model = value
          remaining = tail
        case "--auth-file" :: value :: tail =>
          authFile = Paths.get(value).toAbsolutePath.normalize()
          remaining = tail
        case "--reasoning-effort" :: value :: tail =>
          reasoningEffort = clampReasoningEffort(model, value)
          remaining = tail
        case "--force-login" :: tail =>
          forceLogin = true
          remaining = tail
        case "--help" :: tail =>
          help = true
          remaining = tail
        case head :: tail if head.startsWith("-") =>
          System.err.println(s"Unknown option: $head")
          printHelp()
          sys.exit(1)
        case head :: tail =>
          positionals += head
          remaining = tail
        case Nil =>
          remaining = Nil

    Args(prompt, model, authFile, reasoningEffort, forceLogin, help, positionals.toList)

  private def printHelp(): Unit =
    println(
      s"""Usage:
         |  scala-cli scripts/openai-codex-oauth-demo.scala --prompt \"Tell me a joke\"
         |
         |Options:
         |  --prompt <text>             Prompt to send.
         |  --model <id>                Model id. Default: $DefaultModel
         |  --auth-file <path>          Credential cache file. Default: $DefaultAuthFile
         |  --reasoning-effort <level>  none|minimal|low|medium|high|xhigh. Default: low
         |  --force-login               Ignore cached credentials and run OAuth login again.
         |  --help                      Show this help.
         |
         |Notes:
         |  - Requires a ChatGPT Plus or Pro subscription.
         |  - Opens a browser to complete OAuth.
         |  - Falls back to manual paste if the callback server cannot bind to localhost:$CallbackPort.
            |  - Exposes a tool named run_scala_code(universe: String, scala_code: String).
            |  - run_scala_code executes the provided snippet with scala-cli in the current working directory.
         |""".stripMargin
    )

  private def loadOrLogin(authFile: Path, forceLogin: Boolean): Credentials =
    if !forceLogin then
      loadCredentials(authFile) match
        case Some(credentials) if credentials.expires > System.currentTimeMillis() + 60_000L =>
          return credentials
        case Some(credentials) =>
          println(s"Refreshing cached credentials from $authFile")
          val refreshed = refreshToken(credentials.refresh)
          saveCredentials(authFile, refreshed)
          return refreshed
        case None =>
      ()

    println("Starting OpenAI Codex OAuth login")
    val creds = loginViaBrowser()
    saveCredentials(authFile, creds)
    creds

  private def loadCredentials(authFile: Path): Option[Credentials] =
    if !Files.exists(authFile) then None
    else
      val json = ujson.read(Files.readString(authFile, StandardCharsets.UTF_8))
      Some(
        Credentials(
          access = json("access").str,
          refresh = json("refresh").str,
          expires = json("expires").num.toLong,
          accountId = json("accountId").str
        )
      )

  private def saveCredentials(authFile: Path, creds: Credentials): Unit =
    val parent = authFile.getParent
    if parent != null then Files.createDirectories(parent)
    val json = ujson.Obj(
      "access" -> creds.access,
      "refresh" -> creds.refresh,
      "expires" -> creds.expires,
      "accountId" -> creds.accountId
    )
    Files.writeString(authFile, ujson.write(json, indent = 2), StandardCharsets.UTF_8)
    println(s"Saved credentials to $authFile")

  private def loginViaBrowser(): Credentials =
    val pkce = createPkce()
    val state = randomHex(16)
    val authorizeUri = buildAuthorizeUri(pkce.challenge, state)

    val maybeServer = startCallbackServer(state)
    println("Open this URL to authorize:")
    println(authorizeUri)
    println()
    openBrowser(authorizeUri)

    val code =
      maybeServer match
        case Some(server) =>
          try
            println(s"Waiting for OAuth callback on $RedirectUri")
            try server.codeFuture.get(180, TimeUnit.SECONDS)
            catch
              case _: TimeoutException =>
                println("Timed out waiting for the browser callback.")
                promptForCode(state)
          finally server.stop()
        case None =>
          promptForCode(state)

    val tokenJson = exchangeAuthorizationCode(code, pkce.verifier)
    credentialsFromTokenJson(tokenJson)

  private case class CallbackServer(server: HttpServer, codeFuture: CompletableFuture[String]):
    def stop(): Unit = server.stop(0)

  private def startCallbackServer(expectedState: String): Option[CallbackServer] =
    val future = CompletableFuture[String]()
    try
      val server = HttpServer.create(InetSocketAddress(CallbackHost, CallbackPort), 0)
      server.createContext(
        "/auth/callback",
        new HttpHandler:
          override def handle(exchange: HttpExchange): Unit =
            val params = splitQuery(Option(exchange.getRequestURI.getRawQuery).getOrElse(""))
            val state = params.get("state")
            val code = params.get("code")
            if state.forall(_ != expectedState) then
              respondHtml(exchange, 400, s"${ErrorHtmlPrefix}State mismatch.${ErrorHtmlSuffix}")
            else if code.isEmpty then
              respondHtml(exchange, 400, s"${ErrorHtmlPrefix}Missing authorization code.${ErrorHtmlSuffix}")
            else
              respondHtml(exchange, 200, SuccessHtml)
              future.complete(code.get)
      )
      server.start()
      Some(CallbackServer(server, future))
    catch
      case bind: BindException =>
        System.err.println(
          s"Could not bind http://$CallbackHost:$CallbackPort (${bind.getMessage}). Falling back to manual paste."
        )
        None

  private def respondHtml(exchange: HttpExchange, status: Int, html: String): Unit =
    val body = html.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("content-type", "text/html; charset=utf-8")
    exchange.sendResponseHeaders(status, body.length.toLong)
    val os = exchange.getResponseBody
    try os.write(body)
    finally os.close()

  private def promptForCode(expectedState: String): String =
    println("Paste the authorization code or the full redirect URL:")
    val input = scala.io.StdIn.readLine().trim
    val parsed = parseAuthorizationInput(input)
    parsed.state.foreach { state =>
      if state != expectedState then throw RuntimeException("State mismatch")
    }
    parsed.code.getOrElse(throw RuntimeException("Missing authorization code"))

  private case class ParsedAuthorizationInput(code: Option[String], state: Option[String])

  private def parseAuthorizationInput(input: String): ParsedAuthorizationInput =
    val trimmed = input.trim
    if trimmed.isEmpty then ParsedAuthorizationInput(None, None)
    else
      Try(URI.create(trimmed)).toOption match
        case Some(uri) if uri.getScheme != null && uri.getRawQuery != null =>
          val params = splitQuery(uri.getRawQuery)
          ParsedAuthorizationInput(params.get("code"), params.get("state"))
        case _ if trimmed.contains("code=") =>
          val params = splitQuery(trimmed.replace('&', '&'))
          ParsedAuthorizationInput(params.get("code"), params.get("state"))
        case _ if trimmed.contains("#") =>
          val parts = trimmed.split("#", 2)
          ParsedAuthorizationInput(parts.headOption.filter(_.nonEmpty), parts.lift(1).filter(_.nonEmpty))
        case _ =>
          ParsedAuthorizationInput(Some(trimmed), None)

  private def splitQuery(query: String): Map[String, String] =
    if query == null || query.trim.isEmpty then Map.empty
    else
      query
        .split("&")
        .iterator
        .filter(_.nonEmpty)
        .map { entry =>
          val parts = entry.split("=", 2)
          val key = urlDecode(parts(0))
          val value = if parts.length > 1 then urlDecode(parts(1)) else ""
          key -> value
        }
        .toMap

  private def exchangeAuthorizationCode(code: String, verifier: String): ujson.Value =
    val form = Map(
      "grant_type" -> "authorization_code",
      "client_id" -> ClientId,
      "code" -> code,
      "code_verifier" -> verifier,
      "redirect_uri" -> RedirectUri
    )
    postForm(TokenUrl, form)

  private def refreshToken(refreshToken: String): Credentials =
    val form = Map(
      "grant_type" -> "refresh_token",
      "refresh_token" -> refreshToken,
      "client_id" -> ClientId
    )
    credentialsFromTokenJson(postForm(TokenUrl, form))

  private def credentialsFromTokenJson(json: ujson.Value): Credentials =
    val access = json.obj.get("access_token").map(_.str).getOrElse(throw RuntimeException("Missing access_token"))
    val refresh = json.obj.get("refresh_token").map(_.str).getOrElse(throw RuntimeException("Missing refresh_token"))
    val expiresIn = json.obj.get("expires_in").map(_.num.toLong).getOrElse(throw RuntimeException("Missing expires_in"))
    val accountId = extractAccountId(access).getOrElse(throw RuntimeException("Failed to extract ChatGPT account id"))
    Credentials(access, refresh, System.currentTimeMillis() + expiresIn * 1000L, accountId)

  private def postForm(url: String, data: Map[String, String]): ujson.Value =
    val body = data.map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")
    val request =
      HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("content-type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if response.statusCode() / 100 != 2 then
      throw RuntimeException(s"HTTP ${response.statusCode()} from $url: ${response.body()}")
    ujson.read(response.body())

  private case class ToolInvocation(callId: String, itemId: String, name: String, arguments: ujson.Value)

  private case class TurnResult(replayItems: List[ujson.Value], toolCalls: List[ToolInvocation])

  private case class ScalaToolResult(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean)

  private def streamResponse(credentials: Credentials, model: String, prompt: String, reasoningEffort: String): Unit =
    val sessionId = UUID.randomUUID().toString
    val conversation = mutable.ArrayBuffer[ujson.Value](buildUniverseSetupMessage(), buildUserInput(prompt))
    
    // Add simulated tool call to demonstrate how run_scala_code works
    val (simFunctionCall, simFunctionOutput) = buildSimulatedToolCall()
    conversation += simFunctionCall
    conversation += simFunctionOutput
    
    var continue = true

    while continue do
      val turn = streamTurn(credentials, model, conversation.toList, reasoningEffort, sessionId)
      conversation ++= turn.replayItems

      if turn.toolCalls.nonEmpty then
        for toolCall <- turn.toolCalls do
          conversation += executeToolCall(toolCall)
      else
        continue = false

  private def streamTurn(
      credentials: Credentials,
      model: String,
      conversation: List[ujson.Value],
      reasoningEffort: String,
      sessionId: String
  ): TurnResult =
    val body = ujson.Obj(
      "model" -> model,
      "store" -> false,
      "stream" -> true,
      "instructions" -> "You are a helpful assistant.",
      "input" -> ujson.Arr.from(conversation),
      "text" -> ujson.Obj("verbosity" -> "medium"),
      "include" -> ujson.Arr("reasoning.encrypted_content"),
      "prompt_cache_key" -> sessionId,
      "tools" -> ujson.Arr(runScalaCodeToolDefinition),
      "tool_choice" -> "auto",
      "parallel_tool_calls" -> true,
      "reasoning" -> ujson.Obj(
        "effort" -> clampReasoningEffort(model, reasoningEffort),
        "summary" -> "auto"
      )
    )

    val request =
      HttpRequest
        .newBuilder(URI.create(CodexUrl))
        .timeout(Duration.ofMinutes(10))
        .header("Authorization", s"Bearer ${credentials.access}")
        .header("chatgpt-account-id", credentials.accountId)
        .header("originator", "scala-cli")
        .header("User-Agent", "openai-codex-scala-cli/0.1")
        .header("OpenAI-Beta", "responses=experimental")
        .header("accept", "text/event-stream")
        .header("content-type", "application/json")
        .header("session_id", sessionId)
        .header("x-client-request-id", sessionId)
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
        .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if response.statusCode() / 100 != 2 then
      val errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
      throw RuntimeException(s"HTTP ${response.statusCode()} from Codex endpoint: $errorBody")

    val replayItems = mutable.ArrayBuffer.empty[ujson.Value]
    val toolCalls = mutable.ArrayBuffer.empty[ToolInvocation]
    val reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
    try
      val chunk = mutable.ArrayBuffer.empty[String]
      var done = false
      while !done do
        val line = reader.readLine()
        if line == null then
          done = true
        else if line.isEmpty then
          if chunk.nonEmpty then
            done = handleSseChunk(chunk.toList, replayItems, toolCalls)
            chunk.clear()
        else
          chunk += line
    finally reader.close()
    println()
    TurnResult(replayItems.toList, toolCalls.toList)

  private def buildUserInput(prompt: String): ujson.Value =
    ujson.Obj(
      "role" -> "user",
      "content" -> ujson.Arr(
        ujson.Obj(
          "type" -> "input_text",
          "text" -> prompt
        )
      )
    )

  private def buildUniverseSetupMessage(): ujson.Value =
    ujson.Obj(
      "role" -> "developer",
      "content" -> (
        "Available universes for run_scala_code:\n" +
          "- filesystem\n\n" +
          "Universe identifier semantics:\n" +
          "- The universe argument is an identifier only, not the API itself.\n" +
          "- There is exactly one available universe in this demo: filesystem.\n\n" +
          "filesystem exposes this API:\n" +
          "trait Universe:\n" +
          "  def listFilesInCurrentDirectory(): List[String]\n\n" +
          "When you provide scala_code to run_scala_code, assume there is already an object named universe extending Universe, " +
          "and that all of its members have been imported. That means your code can call listFilesInCurrentDirectory() directly."
      )
    )

  private def buildSimulatedToolCall(): (ujson.Value, ujson.Value) =
    val callId = UUID.randomUUID().toString
    val itemId = UUID.randomUUID().toString
    val argumentsJson = ujson.Obj(
      "universe" -> "filesystem",
      "scala_code" -> "listFilesInCurrentDirectory()"
    )
    val functionCallItem = ujson.Obj(
      "type" -> "function_call",
      "id" -> itemId,
      "call_id" -> callId,
      "name" -> "run_scala_code",
      "arguments" -> ujson.write(argumentsJson)
    )
    val output = ujson.Obj(
      "ok" -> true,
      "tool" -> "run_scala_code",
      "universe" -> "filesystem",
      "exit_code" -> 0,
      "timed_out" -> false,
      "stdout" -> "- foo/",
      "stderr" -> ""
    )
    val functionCallOutput = ujson.Obj(
      "type" -> "function_call_output",
      "call_id" -> callId,
      "output" -> ujson.write(output, indent = 2)
    )
    (functionCallItem, functionCallOutput)

  private def runScalaCodeToolDefinition: ujson.Value =
    ujson.Obj(
      "type" -> "function",
      "name" -> "run_scala_code",
      "description" -> (
        "Execute Scala code with scala-cli. The universe argument is an identifier for the API context being targeted. " +
          "The selected universe is exposed to the subprocess via RUN_SCALA_CODE_UNIVERSE and PI_SCALA_TOOL_UNIVERSE."
      ),
      "parameters" -> ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "universe" -> ujson.Obj(
            "type" -> "string",
            "description" -> "Identifier of the registered API universe the code should operate within."
          ),
          "scala_code" -> ujson.Obj(
            "type" -> "string",
            "description" ->
              "The Scala code to execute. Assume an object extending Universe already exists and all of its members are imported."
          )
        ),
        "required" -> ujson.Arr("universe", "scala_code"),
        "additionalProperties" -> false
      ),
      "strict" -> ujson.Null
    )

  private def handleSseChunk(
      lines: List[String],
      replayItems: mutable.Buffer[ujson.Value],
      toolCalls: mutable.Buffer[ToolInvocation]
  ): Boolean =
    val data =
      lines
        .collect { case line if line.startsWith("data:") => line.stripPrefix("data:").trim }
        .mkString("\n")
        .trim

    if data.isEmpty || data == "[DONE]" then false
    else
      val json = ujson.read(data)
      val eventType = json.obj.get("type").map(_.str).getOrElse("")
      eventType match
        case "response.output_text.delta" =>
          val delta = json.obj.get("delta").map(_.str).getOrElse("")
          print(delta)
          Console.flush()
          false
        case "response.output_item.done" =>
          json.obj.get("item") match
            case Some(item) =>
              item("type").str match
                case "reasoning" | "message" =>
                  replayItems += item
                case "function_call" =>
                  replayItems += item
                  toolCalls += ToolInvocation(
                    callId = item("call_id").str,
                    itemId = item("id").str,
                    name = item("name").str,
                    arguments = parseJsonObject(item.obj.get("arguments").map(_.str).getOrElse("{}"))
                  )
                case _ =>
              false
            case None => false
        case "response.failed" =>
          val message =
            json.obj
              .get("response")
              .flatMap(_.obj.get("error"))
              .flatMap(_.obj.get("message"))
              .map(_.str)
              .getOrElse(data)
          throw RuntimeException(s"Codex response failed: $message")
        case "error" =>
          val code = json.obj.get("code").map(_.str).getOrElse("unknown")
          val message = json.obj.get("message").map(_.str).getOrElse(data)
          throw RuntimeException(s"Codex error [$code]: $message")
        case "response.completed" | "response.done" | "response.incomplete" =>
          true
        case _ =>
          false

  private def executeToolCall(toolCall: ToolInvocation): ujson.Value =
    val output =
      if toolCall.name != "run_scala_code" then
        ujson.Obj(
          "ok" -> false,
          "error" -> s"Unsupported tool: ${toolCall.name}"
        )
      else
        val universeResult = toolCall.arguments.obj.get("universe").map(_.str)
        val scalaCodeResult = toolCall.arguments.obj.get("scala_code").map(_.str)
        (universeResult, scalaCodeResult) match
          case (Some(universe), Some(scalaCode)) =>
            Console.err.println(s"[tool] run_scala_code universe=$universe")
            val result = runScalaCode(universe, scalaCode)
            ujson.Obj(
              "ok" -> (result.exitCode == 0 && !result.timedOut),
              "tool" -> "run_scala_code",
              "universe" -> universe,
              "exit_code" -> result.exitCode,
              "timed_out" -> result.timedOut,
              "stdout" -> result.stdout,
              "stderr" -> result.stderr
            )
          case _ =>
            ujson.Obj(
              "ok" -> false,
              "error" -> "run_scala_code requires string arguments universe and scala_code",
              "received" -> toolCall.arguments
            )

    ujson.Obj(
      "type" -> "function_call_output",
      "call_id" -> toolCall.callId,
      "output" -> ujson.write(output, indent = 2)
    )

  private def runScalaCode(universe: String, scalaCode: String): ScalaToolResult =
    if scalaCode.trim.isEmpty then
      return ScalaToolResult(exitCode = 1, stdout = "", stderr = "scala_code was empty", timedOut = false)

    val preparedCode = prepareScalaToolSource(universe, scalaCode)
    if preparedCode.isLeft then
      return ScalaToolResult(exitCode = 1, stdout = "", stderr = preparedCode.left.get, timedOut = false)

    val tempDir = Files.createTempDirectory("run-scala-code-")
    val codeFile = tempDir.resolve("ToolRun.scala")
    Files.writeString(codeFile, preparedCode.toOption.get, StandardCharsets.UTF_8)

    val processBuilder =
      new ProcessBuilder("scala-cli", "run", codeFile.toString, "--server=false")
        .directory(Paths.get("").toAbsolutePath.normalize().toFile)

    processBuilder.environment().put("RUN_SCALA_CODE_UNIVERSE", universe)
    processBuilder.environment().put("PI_SCALA_TOOL_UNIVERSE", universe)

    val process = processBuilder.start()
    val stdoutFuture = CompletableFuture.supplyAsync(() => readStreamFully(process.getInputStream))
    val stderrFuture = CompletableFuture.supplyAsync(() => readStreamFully(process.getErrorStream))

    val finished = process.waitFor(DefaultToolTimeoutSeconds, TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()

    val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
    val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
    val exitCode = if finished then process.exitValue() else -1

    deleteRecursively(tempDir)
    ScalaToolResult(exitCode = exitCode, stdout = stdout, stderr = stderr, timedOut = !finished)

  private def prepareScalaToolSource(universe: String, scalaCode: String): Either[String, String] =
    universe match
      case "filesystem" =>
        Right(
          """import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

trait Universe:
  def listFilesInCurrentDirectory(): List[String]

object universe extends Universe:
  def listFilesInCurrentDirectory(): List[String] =
    val currentDir = Paths.get(".").toAbsolutePath.normalize()
    val stream = Files.list(currentDir)
    try stream.iterator().asScala.map(_.getFileName.toString).toList.sorted
    finally stream.close()

import universe.*

""" + scalaCode + "\n"
        )
      case other =>
        Left(s"Unknown universe identifier: $other. Available universes: filesystem")

  private def readStreamFully(stream: InputStream): String =
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private def deleteRecursively(path: Path): Unit =
    if Files.notExists(path) then return
    if Files.isDirectory(path) then
      val children = Files.list(path)
      try children.forEach(child => deleteRecursively(child))
      finally children.close()
    Files.deleteIfExists(path)

  private def parseJsonObject(raw: String): ujson.Value =
    Try(ujson.read(raw)).toOption match
      case Some(value: ujson.Value) if value.objOpt.isDefined => value
      case _ => ujson.Obj()

  private case class Pkce(verifier: String, challenge: String)

  private def createPkce(): Pkce =
    val verifierBytes = randomBytes(32)
    val verifier = base64UrlNoPadding(verifierBytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8))
    val challenge = base64UrlNoPadding(digest)
    Pkce(verifier, challenge)

  private def buildAuthorizeUri(challenge: String, state: String): String =
    val params = List(
      "response_type" -> "code",
      "client_id" -> ClientId,
      "redirect_uri" -> RedirectUri,
      "scope" -> Scope,
      "code_challenge" -> challenge,
      "code_challenge_method" -> "S256",
      "state" -> state,
      "id_token_add_organizations" -> "true",
      "codex_cli_simplified_flow" -> "true",
      "originator" -> "scala-cli"
    )
    AuthorizeUrl + "?" + params.map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")

  private def extractAccountId(jwt: String): Option[String] =
    val parts = jwt.split("\\.")
    if parts.length != 3 then None
    else
      val payload = decodeJwtPart(parts(1))
      Try(ujson.read(payload)).toOption
        .flatMap(_.obj.get(ClaimPath))
        .flatMap(_.obj.get("chatgpt_account_id"))
        .map(_.str)
        .filter(_.nonEmpty)

  private def decodeJwtPart(part: String): String =
    val padded = part + "=" * ((4 - part.length % 4) % 4)
    val bytes = Base64.getUrlDecoder.decode(padded)
    new String(bytes, StandardCharsets.UTF_8)

  private def openBrowser(url: String): Unit =
    Try {
      if Desktop.isDesktopSupported() && Desktop.getDesktop.isSupported(Desktop.Action.BROWSE) then
        Desktop.getDesktop.browse(URI.create(url))
      else if sys.props.get("os.name").exists(_.toLowerCase.contains("mac")) then
        Runtime.getRuntime.exec(Array("open", url))
      else if sys.props.get("os.name").exists(_.toLowerCase.contains("linux")) then
        Runtime.getRuntime.exec(Array("xdg-open", url))
      else ()
    }
    ()

  private def randomBytes(size: Int): Array[Byte] =
    val bytes = Array.ofDim[Byte](size)
    SecureRandom().nextBytes(bytes)
    bytes

  private def randomHex(size: Int): String =
    randomBytes(size).map(b => f"${b & 0xff}%02x").mkString

  private def base64UrlNoPadding(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def clampReasoningEffort(model: String, effort: String): String =
    val normalizedModel = model.split("/").lastOption.getOrElse(model)
    val normalizedEffort = effort match
      case "minimal" | "low" | "medium" | "high" | "xhigh" | "none" => effort
      case other => throw RuntimeException(s"Unsupported reasoning effort: $other")
    if (normalizedModel.startsWith("gpt-5.2") || normalizedModel.startsWith("gpt-5.3") || normalizedModel.startsWith("gpt-5.4")) && normalizedEffort == "minimal" then
      "low"
    else normalizedEffort

@main def runOpenAICodexOAuthDemo(args: String*): Unit =
  OpenAICodexOAuthDemo.proc(args.toList)
