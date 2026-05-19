//> using options -preview
package stagedskills

trait StagedSkillWorkspace {
  type Path
  def root(): Path
  def path(relativePath: String): Path
  def list(path: Path): Seq[Path]
  def read(path: Path): String
  def write(path: Path, content: String): Path
  def makeDirectories(path: Path): Path
  def exists(path: Path): Boolean

  /** Write a complete Capybara skill directory.
    *
    * `skillMd` must be a skill `SKILL.md` with YAML frontmatter containing `name` and
    * `description`. `interfaceScala` must begin with using directives such as
    * `//> using options -preview`. `implementationScala` must begin with
    * `//> using file Interface.scala`. `manifestScala` must be exactly a top-level
    * named tuple assigned to `val Manifest`, with fields `universe`, `api`, and `predef`.
    */
  def writeSkill(
      name: String,
      skillMd: String,
      interfaceScala: String,
      implementationScala: String,
      manifestScala: String
  ): Path
}
