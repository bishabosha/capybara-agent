package fs
trait Filesystem extends caps.SharedCapability {
  type Path

  def pwd(): Path
  def list(path: Path): Seq[Path]
}
