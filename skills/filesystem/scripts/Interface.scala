package fs
trait Filesystem {
  trait Path
  def pwd(): Path
  def list(path: Path): Seq[Path]
}
