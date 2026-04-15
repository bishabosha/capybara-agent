package capybara.agent

object ReplExec {
  def compileSigs(sourceCode: String): os.Path = {
    val digest = {
      val bytes = sourceCode.getBytes()
      val hasher = java.security.MessageDigest.getInstance("SHA-256")
      hasher.update(bytes)
      val digest = hasher.digest()
      val base64 = java.util.Base64.getEncoder.encodeToString(digest)
      base64
    }
    val outdir = os.pwd / ".agent-runtime/interface"
    os.makeDir.all(outdir)
    val targetJar = outdir / "sigs.jar"
    val existingSigs = scala.util.Try(os.read(outdir / "sigs.hash"))
    val shouldWrite = !os.exists(targetJar) || existingSigs.map(_ != digest).getOrElse(true)
    if (shouldWrite) {
      os.write.over(outdir / "sigs.hash", digest)
      val tempDir = os.pwd / ".agent-runtime/temp"
      try
        os.makeDir.all(tempDir)
        val tempFile = tempDir / "sigs.scala"
        os.write(tempFile, ScalaAgent.InterfaceLib)
        try
          os.proc(
            Seq[os.Shellable](
              "scala",
              "--power",
              "package",
              "--library",
              "-o",
              tempDir / "sigs.jar",
              tempFile
            )
          ).call()
        finally
          os.remove(tempFile)
        os.move(tempDir / "sigs.jar", targetJar)
      finally os.remove.all(tempDir)
    }
    targetJar
  }
}
