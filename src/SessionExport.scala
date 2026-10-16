package capybara.agent

final case class SessionTurn(prompt: String, state: String, error: Option[String] = None)

/** A diagnostic snapshot; provider-native state stays separate from wire chat messages. */
object SessionExport:
  def snapshot(history: Vector[OllamaClient.ChatMessage], systemPrompt: String,
      workspace: os.Path, thinking: String, completedTurns: Int, currentTurn: Option[SessionTurn],
      executions: ExecutionView): ujson.Value =
    ujson.Obj(
      "formatVersion" -> 1,
      "exportedAt" -> java.time.Instant.now().toString,
      "workspace" -> workspace.toString,
      "thinking" -> thinking,
      "completedTurns" -> completedTurns,
      "historyScope" -> "completed_turns",
      "messages" -> ujson.Arr.from(OllamaClient.ChatMessage.system(systemPrompt).json +: history.map(_.json)),
      "providerMetadata" -> ujson.Arr.from(history.zipWithIndex.collect {
        case (message, index) if message.responseItems.nonEmpty || message.providerState.nonEmpty =>
          ujson.Obj(
            "messageIndex" -> (index + 1),
            "responseItems" -> message.responseItems.map(ujson.Arr.from(_)).getOrElse(ujson.Null),
            "providerState" -> ujson.Obj.from(message.providerState)
          )
      }),
      "currentTurn" -> currentTurn.map(turn => ujson.Obj(
        "prompt" -> turn.prompt,
        "state" -> turn.state,
        "error" -> turn.error.map(ujson.Str(_)).getOrElse(ujson.Null)
      )).getOrElse(ujson.Null),
      "retainedToolCalls" -> ujson.Arr.from(executions.records.map(record => ujson.Obj(
        "id" -> record.id,
        "name" -> record.toolName,
        "universes" -> ujson.Arr.from(record.universes),
        "input" -> record.code,
        "state" -> record.state.toString.toLowerCase,
        "output" -> ujson.Arr.from(record.output.map(chunk => ujson.Obj(
          "channel" -> chunk.channel.label, "text" -> chunk.text)))
      )))
    )
