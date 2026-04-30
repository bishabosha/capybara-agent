package fs
trait Filesystem {
  type Path
  def pwd(): Path
  def list(path: Path): Seq[Path]
}
