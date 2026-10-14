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
      cancellationToken: CancellationToken
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
      case "openai" =>
        OpenAICompatibleClient.request(
          model = setting("CAPYBARA_MODEL").getOrElse(
            throw IllegalArgumentException("CAPYBARA_MODEL is required for the openai provider")
          ),
          baseUrl = setting("CAPYBARA_BASE_URL").getOrElse("https://api.openai.com/v1"),
          apiKey = setting("CAPYBARA_API_KEY").orElse(setting("OPENAI_API_KEY")),
          messages = messages,
          tools = tools,
          toolParsers = toolParsers,
          cancellationToken = cancellationToken
        )
      case other =>
        throw IllegalArgumentException(s"Unknown CAPYBARA_PROVIDER '$other'; expected ollama or openai")
