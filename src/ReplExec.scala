package capybara.agent

object ReplExec {
  def compileSkill(skill: Skill): os.Path = {
    def digestFrom(strings: String*): String = {
      val hasher = java.security.MessageDigest.getInstance("SHA-256")
      for sourceCode <- strings.map(_.getBytes()) do hasher.update(sourceCode)
      val digest = hasher.digest()
      val base64 = java.util.Base64.getEncoder.encodeToString(digest)
      base64
    }
    def withTempFile[T](prefix: os.Path, name: String)(f: os.Path => T): T = {
      val tempFile = prefix / name
      try f(tempFile)
      finally os.remove(tempFile)
    }
    val outdir = os.pwd / ".agent-runtime" / "interface" / skill.name
    os.makeDir.all(outdir)
    val targetSigJar = outdir / "sigs.jar"
    val targetImplJar = outdir / "impl.jar"
    val existingSigs = scala.util.Try(os.read(outdir / "sigs.hash"))
    val digest = digestFrom(skill.interface, skill.code, skill.api, skill.predef)
    val shouldWrite = !os.exists(targetSigJar) || !os
      .exists(targetImplJar) || existingSigs.map(_ != digest).getOrElse(true)
    if (shouldWrite) {
      os.write.over(outdir / "sigs.hash", digest)
      val tempDir = os.pwd / ".agent-runtime/temp"
      try
        os.makeDir.all(tempDir)
        withTempFile(tempDir, "Interface.scala") { tempInterface =>
          os.write(tempInterface, skill.interface)
          withTempFile(tempDir, "sigs.jar") { sigsJar =>
            os.proc(
              Seq[os.Shellable](
                "scala",
                "--power",
                "package",
                "--library",
                "-o",
                sigsJar,
                "-language:experimental.safe",
                tempInterface
              )
            ).call()
            os.copy(sigsJar, targetSigJar, replaceExisting = true)
            withTempFile(tempDir, "Implementation.scala") { tempCode =>
              os.write(tempCode, skill.code)
              withTempFile(tempDir, "impl.jar") { implJar =>
                os.proc(
                  Seq[os.Shellable](
                    "scala",
                    "--power",
                    "package",
                    "--library",
                    "-o",
                    implJar,
                    "--extra-jar",
                    sigsJar,
                    tempCode
                  )
                ).call()
                os.copy(implJar, targetImplJar, replaceExisting = true)
              }
            }
          }
        }
      finally os.remove.all(tempDir)
    }
    outdir
  }
}
