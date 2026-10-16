package capybara.agent

import steps.result.Result

/** Discovery is read-only. Returned documentation becomes ordinary, append-only tool history. */
object SkillDiscovery:
  val SafeModeInstructions =
    """Snippets compile in Scala safe mode; standard-library APIs are not all permitted.
      |Prefer immutable List/Vector with map/foldLeft. Mutable ListBuffer/ArrayBuffer are unavailable here.
      |After a safe-mode rejection, replace the rejected API; do not try a sibling mutable API or add trust annotations.
      |Scala loops use `for (i <- 0 until n)`, not `for (i in ...)`. Local var updates are allowed.
      |Submit one finished implementation. Remove abandoned helpers, alternatives and self-correction comments.
      |Every definition is compiled, even if unused. After a compiler error, make a focused correction and resubmit only the chosen solution.""".stripMargin

  val DiscoveryInstructions =
    """For external actions, choose a skill from the names below.
      |If its purpose is unclear, call describe_skills with universes (empty means all descriptions).
      |Before first using a skill, call read_skills with its universe name. Read its supplied capabilities and calling instructions. Use the inspect-scala-api skill to look up method signatures.
      |Reuse documentation already in this conversation; do not repeatedly load the same skill.
      |Look up unfamiliar methods before calling them. If lookup fails, use inspectScalaApi.list/search; do not guess method names or invent reflection helpers.
      |Discovery calls only return documentation. Then call run_scala_code to perform the action.""".stripMargin

  def systemPrompt(available: Seq[Skill]): String =
    s"""You execute actions with run_scala_code, a Scala 3 snippet runner.
       |For standard-library calculations, strings, collections and dates, use universe "basic" immediately.
       |Do not announce a tool call or draft code in assistant text. Keep simple computations short.
       |Write only the snippet body in scala_code. The harness supplies imports and named capability parameters.
       |Call puts(value) for output, or putsErr(error). These helpers are imported in every universe.
       |Example: {"universe":"basic","scala_code":"puts(List(1, 2, 3).sum)"}.
       |The final expression is discarded. No output means execution succeeded without a computed answer.
       |Do not use global println. Uncaught exceptions are reported as executionError.
       |Skill interfaces document APIs that already exist. Do not copy or implement those traits/classes,
       |construct capability instances, add factories, or write a wrapper function with using parameters.
       |Write your own computation and pure transformations; use supplied capabilities for external actions.
       |Only documented capabilities grant file, network or other external access.
       |$SafeModeInstructions
       |Select one skill with universe, or compose skills with universes. Required skills load automatically.
       |Pure callbacks must not capture capabilities (including output); print outside them.
       |After execution, answer from tool results. Ask one concise question when essential information is missing.
       |
       |$DiscoveryInstructions
       |
       |Available skill universes: ${available.map(_.universe).distinct.sorted.mkString(", ")}
       |""".stripMargin

  private def select(names: Seq[String], available: Seq[Skill]): Result[Vector[Skill], String] =
    Result.catchException { case error => error.getMessage } {
      val known = available.map(skill => skill.universe -> skill).toMap
      names.distinct.map(name => known.getOrElse(name,
        throw IllegalArgumentException(s"Universe not found: $name. Available: ${known.keys.toVector.sorted.mkString(", ")}")))
        .toVector
    }

  def describe(names: Seq[String], available: Seq[Skill]): Result[String, String] =
    select(if names.isEmpty then available.map(_.universe).distinct.sorted else names, available)
      .map(_.map(skill => s"${skill.universe}: ${skill.description}").mkString("\n"))

  def read(names: Seq[String], available: Seq[Skill], builtins: Skill): Result[String, String] =
    if names.isEmpty then Result.Err("read_skills requires at least one universe name; use describe_skills to browse.")
    else select(names, available).flatMap { selected =>
      Skills.resolve(names, available, builtins).map { dependencies =>
        Skills.renderPrompt("\n\n", (dependencies ++ selected).distinctBy(_.universe), includeInterfaces = false)
      }
    }
