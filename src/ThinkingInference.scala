package capybara.agent

object ThinkingInference {

  enum ThinkingMode:
    case On, Off, Auto

    def displayName: String =
      this match
        case On   => "on"
        case Off  => "off"
        case Auto => "auto"

  object ThinkingMode:
    def parse(value: String): Option[ThinkingMode] =
      value.trim.toLowerCase match
        case "on" | "true" | "yes"     => Some(On)
        case "off" | "false" | "no"    => Some(Off)
        case "auto" | "automatic" | "" => Some(Auto)
        case _                         => None

  def shouldThink(query: String, mode: ThinkingMode): Boolean = {
    mode match
      case ThinkingMode.On   => true
      case ThinkingMode.Off  => false
      case ThinkingMode.Auto =>
        val q = query.toLowerCase
        def hasAny(words: String*): Boolean =
          words.exists(q.contains)

        val explicitOff =
          hasAny("don't think", "dont think", "no thinking", "think off", "quick", "just ")
        val explicitOn =
          hasAny(
            "think carefully",
            "reason through",
            "analyze",
            "analyse",
            "debug",
            "design",
            "architecture",
            "tradeoff",
            "trade-off"
          )
        val likelyBasic =
          hasAny(
            "calculate",
            "convert",
            "format",
            "sort",
            "sum",
            "list"
          )
        val likelyComplex =
          hasAny(
            "why",
            "fix",
            "failing",
            "failed",
            "error",
            "exception",
            "refactor",
            "implement",
            "multiple files",
            "across files",
            "codebase",
            "root cause",
            "test failure"
          ) || q.length > 500

        if explicitOff then false
        else if explicitOn then true
        else if likelyBasic && !likelyComplex then false
        else likelyComplex
  }
}
