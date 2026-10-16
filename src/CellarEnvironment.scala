package capybara.agent

/** Pin both readers to the format produced by the harness compiler, independently of PATH's cellar. */
private[agent] object CellarEnvironment:
  val Cli = "org.virtuslab:cellar-cli_3:0.1.0-M13"
  val Reader = "ch.epfl.scala:tasty-query_3:1.10.0"
  private def versions = s"$Cli|$Reader|${ReplExec.ScalaVersion}|${cellar.CapybaraPrinterPatch.Revision}"

  /** Only the patched printer classes shadow the pinned library; all inspection remains in Cellar. */
  private def preparePrinter(directory: os.Path): os.Path =
    val source = os.Path(cellar.CapybaraPrinterPatch.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    def included(name: String): Boolean =
      Seq("cellar/TypePrinter", "cellar/DetectedLanguage", "cellar/CapybaraPrinterPatch")
        .exists(name.startsWith) && name.endsWith(".class")
    val target = directory / "printer-patch.jar"
    val output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(target.toNIO))
    def write(name: String, bytes: Array[Byte]): Unit =
      val entry = new java.util.jar.JarEntry(name)
      entry.setTime(0L)
      output.putNextEntry(entry)
      output.write(bytes)
      output.closeEntry()
    try
      if os.isDir(source) then
        os.walk(source / "cellar").filter(os.isFile(_)).sortBy(_.toString).foreach { file =>
          val name = file.relativeTo(source).toString
          if included(name) then write(name, os.read.bytes(file))
        }
      else
        val jar = new java.util.jar.JarFile(source.toIO)
        try
          val entries = jar.entries()
          while entries.hasMoreElements do
            val entry = entries.nextElement()
            if included(entry.getName) then
              val stream = jar.getInputStream(entry)
              try write(entry.getName, stream.readAllBytes()) finally stream.close()
        finally jar.close()
    finally output.close()
    target

  def prepare(workspace: os.Path): Unit =
    prepareWith(workspace, (arguments, directory) => {
      val result = os.proc(arguments).call(cwd = directory, check = false, timeout = 120000,
        stdout = os.Pipe, stderr = os.Pipe)
      require(result.exitCode == 0, "Cellar runtime setup failed: " + result.err.text())
      result.out.text().trim
    })

  private[agent] def prepareWith(workspace: os.Path, run: (Seq[String], os.Path) => String): Unit = synchronized {
    val directory = workspace / ".agent-runtime/cellar"
    os.makeDir.all(directory)
    val classpathFile = directory / "classpath.txt"
    val ready = directory / "ready"
    val patch = preparePrinter(directory)
    val digest = java.util.Base64.getEncoder.encodeToString(
      java.security.MessageDigest.getInstance("SHA-256").digest(os.read.bytes(patch)))
    val expectedVersions = versions + "|" + digest
    val valid = os.exists(ready) && os.read(ready) == expectedVersions && os.exists(classpathFile) &&
      os.read(classpathFile).split(java.io.File.pathSeparator).forall(path =>
        path.nonEmpty && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path)))
    if !valid then
      if os.exists(ready) then os.remove(ready)
      val dependencies = run(Seq("cs", "fetch", "--quiet", "--classpath", Cli,
        "--force-version", Reader,
        "--force-version", s"org.scala-lang:scala3-compiler_3:${ReplExec.ScalaVersion}",
        "--force-version", s"org.scala-lang:scala3-tasty-inspector_3:${ReplExec.ScalaVersion}"), directory)
      require(dependencies.nonEmpty && !dependencies.contains('\n'), "Invalid Cellar runtime classpath")
      val classpath = patch.toString + java.io.File.pathSeparator + dependencies
      os.write.over(classpathFile, classpath)
      val javaExecutable = os.Path(sys.props("java.home")) / "bin/java"
      // This is an isolated tool runtime, not the user's project or global Cellar configuration.
      val _ = run(Seq(javaExecutable.toString, "-cp", classpath, "cellar.cli.CellarApp", "telemetry", "disable"), directory)
      os.write.over(ready, expectedVersions)
  }
