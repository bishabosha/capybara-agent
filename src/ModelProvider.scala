package capybara.agent

import upickle.default.*

object ModelProvider:
  private def setting(name: String): Option[String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  def request[T: Writer](
      messages: Seq[OllamaClient.ChatMessage],
      tools: Vector[OllamaClient.ToolDefinition],
      toolParsers: Map[String, ReadWriter[T]],
      contextWindow: Int,
      keepAlive: String,
      think: Boolean,
      cancellationToken: CancellationToken,
      onStatus: String => Unit = _ => (),
      onNotice: String => Unit = _ => ()
  ): OllamaClient.ChunkRequest[OllamaClient.Chunk[T]] =
    setting("CAPYBARA_PROVIDER").getOrElse("ollama") match
      case "ollama" =>
        OllamaClient.request(
          model = setting("CAPYBARA_MODEL").getOrElse(ScalaAgent.Model),
          messages = messages,
          tools = tools,
          toolParsers = toolParsers,
          contextWindow = contextWindow,
          keepAlive = keepAlive,
          think = think,
          cancellationToken = cancellationToken
        )
      case "apple-fm" =>
        AppleFmClient.request(messages, tools, toolParsers, cancellationToken,
          python = setting("CAPYBARA_APPLE_FM_PYTHON").getOrElse(""),
          model = setting("CAPYBARA_MODEL").getOrElse("system"), onStatus = onStatus, onNotice = onNotice)
      case provider @ ("openai" | "openai-responses") =>
        val model = setting("CAPYBARA_MODEL").getOrElse(
          throw IllegalArgumentException(s"CAPYBARA_MODEL is required for the $provider provider")
        )
        val baseUrl = setting("CAPYBARA_BASE_URL").getOrElse("https://api.openai.com/v1")
        val apiKey = setting("CAPYBARA_API_KEY").orElse(setting("OPENAI_API_KEY"))
        if provider == "openai-responses" then OpenAIResponsesClient.request(
          model = model,
          baseUrl = baseUrl,
          apiKey = apiKey,
          messages = messages,
          tools = tools,
          toolParsers = toolParsers,
          cancellationToken = cancellationToken
        )
        else OpenAICompatibleClient.request(
          model = model,
          baseUrl = baseUrl,
          apiKey = apiKey,
          messages = messages,
          tools = tools,
          toolParsers = toolParsers,
          cancellationToken = cancellationToken
        )
      case other =>
        throw IllegalArgumentException(
          s"Unknown CAPYBARA_PROVIDER '$other'; expected ollama, openai, openai-responses, or apple-fm")
