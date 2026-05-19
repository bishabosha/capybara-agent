//> using options -preview
package stagedskills

final case class SkillChecklist(
    skill: String,
    written: Seq[String],
    remaining: Seq[String],
    complete: Boolean
)

trait StagedSkillWorkspaceAccess {
  def run[T](f: stagedskills.StagedSkillWorkspace^ => T): T
}

trait StagedSkillWorkspace extends caps.Mutable {
  type Path
  def root(): Path
  def path(relativePath: String): Path
  def list(path: Path): Seq[Path]
  def read(path: Path): String
  update def write(path: Path, content: String): Path
  def makeDirectories(path: Path): Path
  def exists(path: Path): Boolean

  /** Return which required files are written or still missing for a staged skill. */
  def checklist(name: String): SkillChecklist

  /** Write `SKILL.md`, which must have YAML frontmatter containing `name` and `description`.
    *
    * A single `run_scala_code` call may write at most one file. Make this write call
    * the final expression, read the returned checklist, and make a later call for
    * the next missing file.
    */
  update def writeSkillMarkdown(name: String, skillMd: String): SkillChecklist

  /** Write `scripts/Interface.scala`, which must begin with Scala CLI using directives.
    *
    * A single `run_scala_code` call may write at most one file. Make this write call
    * the final expression, read the returned checklist, and make a later call for
    * the next missing file.
    *
    * Public interfaces must describe a narrow, task-level capability. They must not
    * expose host filesystem path types, shell/process APIs, or manifest helper values.
    */
  update def writeInterface(name: String, interfaceScala: String): SkillChecklist

  /** Write `scripts/Implementation.scala`, which must include `//> using file Interface.scala`
    * before its package declaration and exactly one public
    * `@assumeSafe val impl: ExplicitApiInterface = ...`.
    *
    * A single `run_scala_code` call may write at most one file. Make this write call
    * the final expression, read the returned checklist, and make a later call for
    * the next missing file.
    */
  update def writeImplementation(name: String, implementationScala: String): SkillChecklist

  /** Write only `scripts/Manifest.scala` for a staged skill.
    *
    * A single `run_scala_code` call may write at most one file. Make this write call
    * the final expression, read the returned checklist, and make a later call for
    * the next missing file.
    *
    * The file is rendered as a top-level named tuple assigned to `val Manifest`, with
    * fields `universe`, `api`, and `predef`. The manifest `api` and `predef` strings
    * are generated from the binding name, public interface FQN, and internal
    * implementation package; do not pass raw manifest source or full trait definitions.
    */
  update def writeManifest(
      name: String,
      universe: String,
      bindingName: String,
      interfaceFqn: String,
      implementationPackage: String
  ): SkillChecklist
}