package capybara.agent

import steps.result.Result

/** Host setup only: copy tracked starters and prepare skill builds without invoking factories. */
object WorkshopSetup:
  case class Workspace(name: String, root: os.Path, environment: os.Path, created: Boolean)

  private[agent] def shellQuote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"

  def create(repository: os.Path, directory: os.Path): Seq[Workspace] =
    val starters = Seq(
      "coding" -> (repository / "examples/coding-workspace"),
      "github" -> (repository / "presentations/capybara-architecture/workshop/project")
    )
    // Validate all destinations before copying anything. Existing workspaces and
    // edited environment files belong to the participant and are never replaced.
    val planned = starters.map { (name, source) =>
      require(os.isDir(source), s"Missing workshop starter: $source. Run from the Capybara checkout.")
      val target = directory / name
      val environment = directory / s"$name.env"
      val contents = s"export CAPYBARA_CODING_ROOT=${shellQuote(target.toString)}\n"
      require(!os.exists(target) || os.isDir(target), s"Workspace path is not a directory: $target")
      require(!os.exists(environment) || (os.isFile(environment) && os.read(environment) == contents),
        s"Environment file already contains different settings: $environment. Choose another --directory.")
      (name, source, target, environment, contents)
    }
    os.makeDir.all(directory)
    planned.map { (name, source, target, environment, contents) =>
      val created = !os.exists(target)
      if created then os.copy(source, target)
      if !os.exists(environment) then os.write(environment, contents)
      Workspace(name, target, environment, created)
    }

  @mainargs.main(doc = "Prepare both workshop workspaces and skill builds; preserve existing participant edits.")
  def main(@mainargs.arg(doc = "Workspace parent; choose a new directory for a fresh workshop")
      directory: String = ".agent-runtime/demo-workspaces"): Unit =
    val builtins = load("builtin-skills/capybara-builtins")
    val skills = Seq(load("skills/coding"), load("skills/github"))
    ReplExec.prepareSkills(builtins, skills) match
      case Result.Err(error) => throw IllegalStateException(error)
      case Result.Ok(_) => ()
    val workspaces = create(os.pwd, os.Path(directory, os.pwd))
    println("Workshop ready. Skill APIs and implementations are built.")
    for workspace <- workspaces do
      println(s"${workspace.name}: ${workspace.root} (${if workspace.created then "created" else "kept existing edits"})")
    println("\nRun each numbered step in the same Bash or Zsh terminal.")
    println("\nCoding demo — step 1: load workspace settings")
    println(s"source ${shellQuote(workspaces.head.environment.toString)}")
    println("\nCoding demo — step 2: run the snippet")
    println("./mill run skill-run --skill coding --file examples/snippets/coding.scala")
    println("\nGitHub demo — step 1: load workspace settings")
    println(s"source ${shellQuote(workspaces(1).environment.toString)}")
    println("\nGitHub demo — step 2: configure the remote repository and token")
    println("# Set CAPYBARA_GITHUB_REPOSITORY and supply CAPYBARA_GITHUB_TOKEN in your shell.")
    println("\nGitHub demo — step 3: run the snippet")
    println("./mill run skill-run --skill github --file examples/snippets/github-lab.scala")
    println("\nSetup creates local files only. It does not contact GitHub or store credentials.")

  private def load(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(value) => value
    case Result.Err(error) => throw IllegalStateException(error)
