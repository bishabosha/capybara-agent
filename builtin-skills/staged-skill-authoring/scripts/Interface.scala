//> using options -preview
package stagedskills

final case class SkillChecklist(
    skill: String,
    written: Seq[String],
    remaining: Seq[String],
    complete: Boolean
)

trait StagedSkillWorkspaceProvider {
  def current(): StagedSkillWorkspace
}

trait StagedSkillWorkspace {
  type Path
  def root(): Path
  def path(relativePath: String): Path
  def list(path: Path): Seq[Path]
  def read(path: Path): String
  def write(path: Path, content: String): Path
  def makeDirectories(path: Path): Path
  def exists(path: Path): Boolean

  /** Return which required files are written or still missing for a staged skill. */
  def checklist(name: String): SkillChecklist

  /** Write `SKILL.md`, which must have YAML frontmatter containing `name` and `description`. */
  def writeSkillMarkdown(name: String, skillMd: String): SkillChecklist

  /** Write `scripts/Interface.scala`, which must begin with Scala CLI using directives. */
  def writeInterface(name: String, interfaceScala: String): SkillChecklist

  /** Write `scripts/Implementation.scala`, which must include `//> using file Interface.scala`
    * before its package declaration and exactly one public
    * `@assumeSafe val impl: ExplicitApiInterface = ...`.
    */
  def writeImplementation(name: String, implementationScala: String): SkillChecklist

  /** Write only `scripts/Manifest.scala` for a staged skill.
    *
    * The file is rendered as a top-level named tuple assigned to `val Manifest`, with
    * fields `universe`, `api`, and `predef`.
    */
  def writeManifest(
      name: String,
      universe: String,
      api: String,
      predef: String
  ): SkillChecklist
}
