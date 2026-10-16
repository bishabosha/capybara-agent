package capybara.agent

import java.util.concurrent.atomic.AtomicLong

enum ToolOutputChannel:
  case Stdout, Stderr
  def label: String = this match
    case Stdout => "stdout"
    case Stderr => "stderr"

enum ToolExecutionState:
  case Queued, Running, Succeeded, Failed, Cancelled

enum ExecutionSelection:
  case Latest, Older, Newer
  case ById(id: Long)

final case class ToolOutputChunk(channel: ToolOutputChannel, text: String)
final case class ToolExecution(
    id: Long,
    universes: Vector[String],
    code: String,
    state: ToolExecutionState = ToolExecutionState.Queued,
    output: Vector[ToolOutputChunk] = Vector.empty,
    toolName: String = "run_scala_code"
)
final case class ExecutionView(records: Vector[ToolExecution], selectedId: Option[Long], following: Boolean):
  def selected: Option[ToolExecution] = selectedId.flatMap(id => records.find(_.id == id)).orElse(records.lastOption)

/** Session-owned history. The logger serializes writes and publishes immutable snapshots. */
final class ToolExecutionHistory(val limit: Int = 100, val textLimit: Int = 32768):
  require(limit > 0 && textLimit > 0)
  private var records = Vector.empty[ToolExecution]
  private var pinned = Option.empty[Long]

  def start(id: Long, universes: Seq[String], code: String, toolName: String = "run_scala_code"): Unit =
    val displayed = if code.length > textLimit then code.take(textLimit) + "\n// [code truncated in history]" else code
    records = (records :+ ToolExecution(id, universes.toVector, displayed, toolName = toolName)).takeRight(limit)
    if pinned.exists(id => !records.exists(_.id == id)) then pinned = None

  def state(id: Long, state: ToolExecutionState): Unit =
    records = records.map(record => if record.id == id then record.copy(state = state) else record)

  def append(id: Long, channel: ToolOutputChannel, text: String): Unit =
    records = records.map { record =>
      if record.id != id then record
      else
        val chunks = record.output.lastOption match
          case Some(last) if last.channel == channel => record.output.init :+ last.copy(text = last.text + text)
          case _ => record.output :+ ToolOutputChunk(channel, text)
        var remaining = textLimit
        val kept = chunks.takeRight(512).reverseIterator.flatMap { chunk =>
          val tail = chunk.text.takeRight(remaining)
          remaining = (remaining - tail.length).max(0)
          Option.when(tail.nonEmpty)(chunk.copy(text = if tail.head.isLowSurrogate then tail.drop(1) else tail))
        }.toVector.reverse
        record.copy(output = kept)
    }

  def snapshot: ExecutionView = ExecutionView(records, pinned.orElse(records.lastOption.map(_.id)), pinned.isEmpty)

  def select(selection: ExecutionSelection): Either[String, String] =
    if records.isEmpty then Left("No tool calls yet.")
    else
      val index = snapshot.selected.flatMap(selected => records.indexWhere(_.id == selected.id) match
        case -1 => None
        case value => Some(value)).getOrElse(records.size - 1)
      selection match
        case ExecutionSelection.Latest =>
          pinned = None
          Right(s"Following latest execution #${records.last.id}.")
        case ExecutionSelection.Older =>
          pinned = Some(records((index - 1).max(0)).id)
          Right(s"Viewing execution #${pinned.get}.")
        case ExecutionSelection.Newer =>
          pinned = Some(records((index + 1).min(records.size - 1)).id)
          Right(s"Viewing execution #${pinned.get}.")
        case ExecutionSelection.ById(id) =>
          if records.exists(_.id == id) then
            pinned = Some(id)
            Right(s"Viewing execution #$id.")
          else Left(s"Execution #$id is not in retained history.")

  def renderList: String =
    if records.isEmpty then "No tool calls yet."
    else records.map { record =>
      val preview = record.code.linesIterator.find(_.trim.nonEmpty).getOrElse("").take(90)
      val name = if record.toolName == "run_scala_code" then "" else record.toolName + " "
      s"#${record.id} ${record.state.toString.toLowerCase} [${record.universes.mkString(",")}] $name$preview"
    }.mkString("\n")

  def clear(): Unit =
    records = Vector.empty
    pinned = None

object ToolExecutionHistory:
  private val ids = AtomicLong(0)
  def nextId(): Long = ids.incrementAndGet()
