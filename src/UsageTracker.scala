package capybara.agent

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

final class UsageTracker(initialContextWindow: Option[Int]):
  private val totalPromptTokens = AtomicLong(0)
  private val totalCompletionTokens = AtomicLong(0)
  private val latestPromptTokens = AtomicReference(Option.empty[Int])
  private val latestCompletionTokens = AtomicReference(Option.empty[Int])
  private val contextWindow = AtomicReference(initialContextWindow)

  def record(usage: OllamaClient.Usage): Unit =
    usage.promptEvalCount.foreach { count =>
      totalPromptTokens.addAndGet(count.toLong)
      latestPromptTokens.set(Some(count))
    }
    usage.evalCount.foreach { count =>
      totalCompletionTokens.addAndGet(count.toLong)
      latestCompletionTokens.set(Some(count))
    }

  def setContextWindow(value: Option[Int]): Unit =
    contextWindow.set(value)

  def snapshot: UsageTracker.Snapshot =
    val prompt = totalPromptTokens.get()
    val completion = totalCompletionTokens.get()
    UsageTracker.Snapshot(
      promptTokens = prompt,
      completionTokens = completion,
      latestPromptTokens = latestPromptTokens.get(),
      latestCompletionTokens = latestCompletionTokens.get(),
      contextWindow = contextWindow.get()
    )

  def render: String =
    snapshot.render

object UsageTracker:
  final case class Snapshot(
      promptTokens: Long,
      completionTokens: Long,
      latestPromptTokens: Option[Int],
      latestCompletionTokens: Option[Int],
      contextWindow: Option[Int]
  ):
    private def tokenCount(value: Long): String = f"$value%,d"
    private def tokenCount(value: Int): String = f"$value%,d"

    private val currentContextTokens: Option[Int] =
      latestPromptTokens.map(_ + latestCompletionTokens.getOrElse(0))

    def render: String =
      val totalTokens = promptTokens + completionTokens
      val contextUsed = currentContextTokens
        .map(tokens => s"${tokenCount(tokens)} tokens")
        .getOrElse("not measured yet")
      val spaceLeft = (contextWindow, currentContextTokens) match
        case (Some(window), Some(used)) => s"${tokenCount((window - used).max(0))} tokens"
        case (Some(_), None)            => "not measured yet"
        case (None, _)                  => "unknown"
      val window = contextWindow.map(tokenCount).getOrElse("unknown")
      val latestPrompt = latestPromptTokens.map(tokenCount).getOrElse("not measured yet")
      val latestCompletion = latestCompletionTokens.map(tokenCount).getOrElse("not measured yet")

      s"""|Usage since launch:
          |  prompt tokens: ${tokenCount(promptTokens)}
          |  completion tokens: ${tokenCount(completionTokens)}
          |  total tokens: ${tokenCount(totalTokens)}
          |Context:
          |  window: $window
          |  current used: $contextUsed
          |  space left: $spaceLeft
          |Latest request:
          |  prompt tokens: $latestPrompt
          |  completion tokens: $latestCompletion
          |""".stripMargin
