//> using file Interface.scala
package stagedskills.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import caps.any
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path as JPath}
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.jdk.CollectionConverters.given

private object CurrentSession {
  private val SessionIdProperty = "capybara.agent.stagedSkillSessionId"

  def rootPath(): JPath = {
    val rawSessionId =
      Option(java.lang.System.getProperty(SessionIdProperty))
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(throw IllegalStateException("missing staged skill session entitlement"))
    val sessionId =
      try UUID.fromString(rawSessionId).toString
      catch {
        case _: IllegalArgumentException =>
          throw IllegalStateException("invalid staged skill session entitlement")
      }
    JPath.of(".agent-runtime", "staged-skills", sessionId).toAbsolutePath.normalize()
  }
}

private final class LocalStagedSkillWorkspace(rootPath: JPath)
    extends stagedskills.StagedSkillWorkspace
    with caps.Mutable {
  private val cwdPath = JPath.of(".").toAbsolutePath.normalize()
  private val scalaVersion = "3.9.0-RC1-bin-20260430-a24622b-NIGHTLY"
  private val requiredFiles: Vector[String] =
    Vector(
      "SKILL.md",
      "scripts/Interface.scala",
      "scripts/Implementation.scala",
      "scripts/Manifest.scala"
    )
  private var fileWrittenInCall: Option[String] = None

  class Path private[LocalStagedSkillWorkspace] (
      private[LocalStagedSkillWorkspace] val inner: JPath
  ) {
    override def toString: String =
      if inner == rootPath then "."
      else rootPath.relativize(inner).toString
  }

  private def checked(path: JPath): JPath = {
    val normalized = path.toAbsolutePath.normalize()
    if !normalized.startsWith(rootPath) then
      throw IllegalArgumentException(
        s"path escapes staged skill workspace: $normalized"
      )
    rejectSymbolicLinks(normalized)
    normalized
  }

  private def rejectSymbolicLinks(path: JPath): Unit = {
    val start = if path.startsWith(cwdPath) then cwdPath else rootPath
    val relative = start.relativize(path)
    var current = start
    var index = 0
    while index < relative.getNameCount do {
      current = current.resolve(relative.getName(index))
      if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current) then
        throw IllegalArgumentException(
          s"symbolic links are not visible: ${displayPath(current)}"
        )
      index += 1
    }
  }

  private def displayPath(path: JPath): String =
    if path.startsWith(rootPath) then rootPath.relativize(path).toString
    else path.toString

  private def checkedRelative(relativePath: String): JPath = {
    val raw = JPath.of(relativePath)
    if raw.isAbsolute then
      throw IllegalArgumentException(s"absolute paths are not visible: $relativePath")
    rejectParentSegments(raw, relativePath)
    checked(rootPath.resolve(raw))
  }

  private def rejectParentSegments(path: JPath, original: String): Unit = {
    val iterator = path.iterator()
    while iterator.hasNext do {
      if iterator.next().toString == ".." then
        throw IllegalArgumentException(s"parent path segments are not visible: $original")
    }
  }

  private def checkedSkillName(name: String): String = {
    val raw = JPath.of(name)
    if (
        name.trim.isEmpty ||
        raw.isAbsolute ||
        raw.getNameCount != 1 ||
        name == "." ||
        name == ".."
      )
    then
      throw IllegalArgumentException(
        "skill name must be one non-empty directory name inside the staged workspace"
      )
    name
  }

  private def checkedSkillMarkdown(skillMd: String): String = {
    val hasFrontmatter = skillMd.startsWith("---\n") && skillMd.drop(4).contains("\n---")
    val hasName = skillMd.linesIterator.exists(_.startsWith("name:"))
    val hasDescription = skillMd.linesIterator.exists(_.startsWith("description:"))
    if !hasFrontmatter || !hasName || !hasDescription then
      throw IllegalArgumentException(
        "SKILL.md must have YAML frontmatter containing name and description"
      )
    skillMd
  }

  private def directivePrefix(content: String): Vector[String] =
    content.linesIterator
      .dropWhile(_.trim.isEmpty)
      .takeWhile(line => line.startsWith("//> using ") || line.trim.isEmpty)
      .toVector

  private def fileDirectivePrefix(content: String): Vector[String] =
    content.linesIterator
      .takeWhile(line => line.startsWith("//> using ") || line.trim.isEmpty)
      .toVector

  private def packageName(content: String): Option[String] =
    content.linesIterator.collectFirst {
      case line if line.trim.startsWith("package ") =>
        line.trim.stripPrefix("package ").trim
    }

  private def checkedInterface(interfaceScala: String): String = {
    if directivePrefix(interfaceScala).isEmpty then
      throw IllegalArgumentException(
        "Interface.scala must begin with Scala CLI using directives"
      )
    if packageName(interfaceScala).isEmpty then
      throw IllegalArgumentException("Interface.scala must declare a package")
    val disallowedFragments =
      Vector(
        "java.nio.file" -> "Interface.scala must not expose Java NIO filesystem types",
        "java.io.File" -> "Interface.scala must not expose java.io.File",
        "os.Path" -> "Interface.scala must not expose os.Path",
        "scala.sys.process" -> "Interface.scala must not expose shell/process APIs",
        "sys.process" -> "Interface.scala must not expose shell/process APIs"
      )
    disallowedFragments
      .collectFirst {
        case (fragment, message) if interfaceScala.contains(fragment) => message
      }
      .foreach(message => throw IllegalArgumentException(message))
    val manifestHelperPattern = raw"(?m)^\s*(val|def)\s+(api|predef|Manifest)\b".r
    if manifestHelperPattern.findFirstIn(interfaceScala).isDefined then
      throw IllegalArgumentException(
        "Interface.scala must not define manifest api, predef, or Manifest helpers"
      )
    interfaceScala
  }

  private def checkedImplementation(implementationScala: String): String = {
    val hasInterfaceDirective =
      directivePrefix(implementationScala).contains("//> using file Interface.scala")
    if !hasInterfaceDirective then
      throw IllegalArgumentException(
        "Implementation.scala must include `//> using file Interface.scala` before its package declaration"
      )
    val pkg = packageName(implementationScala).getOrElse {
      throw IllegalArgumentException("Implementation.scala must declare an internal package")
    }
    if !pkg.endsWith(".internal") then
      throw IllegalArgumentException("Implementation.scala package must end with `.internal`")
    if !implementationScala.linesIterator.exists(_.trim == "import caps.assumeSafe") then
      throw IllegalArgumentException("Implementation.scala must import `caps.assumeSafe`")
    val implValuePattern = raw"(?m)^\s*@assumeSafe\s*(?:\n\s*)?val\s+impl\s*:\s*[^=]+=".r
    if implValuePattern.findAllMatchIn(implementationScala).size != 1 then
      throw IllegalArgumentException(
        "Implementation.scala must expose exactly one public top-level member `@assumeSafe val impl: ExplicitApiInterface = ...`, where `@assumeSafe` was previously imported via `import caps.assumeSafe`, and `ExplicitApiInterface` is the public facing API of `Interface.scala`."
      )
    val publicImplDefPattern = raw"(?m)^\s*def\s+impl\b".r
    if publicImplDefPattern.findFirstIn(implementationScala).isDefined then
      throw IllegalArgumentException(
        "Implementation.scala must expose `impl` as a typed value, not a method"
      )
    val publicImplObjectPattern = raw"(?m)^\s*object\s+impl\b".r
    if publicImplObjectPattern.findFirstIn(implementationScala).isDefined then
      throw IllegalArgumentException("Implementation.scala must not expose `object impl`")
    implementationScala
  }

  private def deleteRecursively(path: JPath): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val stream = Files.walk(path)
      try
        stream
          .iterator()
          .asScala
          .toSeq
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists)
      finally stream.close()

  private def runCompiler(args: Seq[String], label: String): Unit = {
    def strippedAnsi(str: String): String =
      str.replaceAll("\u001B\\[[;\\d]*m", "")
    val command = java.util.ArrayList[String]()
    args.foreach(command.add)
    val process =
      java.lang.ProcessBuilder(command).directory(cwdPath.toFile).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exitCode =
      try process.waitFor()
      catch {
        case _: InterruptedException =>
          process.destroyForcibly()
          Thread.currentThread().interrupt()
          throw IllegalStateException(s"$label was interrupted")
      }
    if exitCode != 0 then
      val trimmedOutput =
        strippedAnsi(output)
          .linesIterator
          .dropWhile(!_.startsWith("Compiling project (Scala"))
          .mkString("\n")
      throw IllegalArgumentException(
        s"""$label failed with exit code $exitCode:
           |${trimmedOutput}""".stripMargin
      )
  }

  private def compileInterfaceToJar(
      tempDir: JPath,
      interfaceScala: String
  ): JPath = {
    val tempInterface = tempDir.resolve("Interface.scala")
    val sigsJar = tempDir.resolve("sigs.jar")
    Files.writeString(tempInterface, interfaceScala, StandardCharsets.UTF_8)
    runCompiler(
      Vector(
        "scala",
        "--power",
        "package",
        "-S",
        scalaVersion,
        "--library",
        "-f",
        "-o",
        sigsJar.toString,
        "-color:never",
        "-language:experimental.safe",
        tempInterface.toString
      ),
      "Interface.scala safe-mode compilation"
    )
    sigsJar
  }

  private def validateInterfaceCompiles(interfaceScala: String): Unit =
    val tempDir = Files.createTempDirectory("capybara-staged-skill-validation-")
    try
      compileInterfaceToJar(tempDir, interfaceScala)
      ()
    finally deleteRecursively(tempDir)

  private def implementationCompileSource(
      interfaceScala: String,
      implementationScala: String
  ): String = {
    val interfaceDirectives = fileDirectivePrefix(interfaceScala)
    val (implementationDirectives, implementationBody) =
      implementationScala.linesIterator.toVector.span(line =>
        line.startsWith("//> using ") || line.trim.isEmpty
      )
    val mergedDirectives =
      (interfaceDirectives ++ implementationDirectives).filterNot(line =>
        line.startsWith("//> using file ") || line.startsWith("//> using files ")
      )
    (mergedDirectives ++ implementationBody).mkString(java.lang.System.lineSeparator())
  }

  private def validateImplementationCompiles(
      interfaceScala: String,
      implementationScala: String
  ): Unit =
    val tempDir = Files.createTempDirectory("capybara-staged-skill-validation-")
    try
      val sigsJar = compileInterfaceToJar(tempDir, interfaceScala)
      val tempImplementation = tempDir.resolve("Implementation.scala")
      val implJar = tempDir.resolve("impl.jar")
      Files.writeString(
        tempImplementation,
        implementationCompileSource(interfaceScala, implementationScala),
        StandardCharsets.UTF_8
      )
      runCompiler(
        Vector(
          "scala",
          "--power",
          "package",
          "-S",
          scalaVersion,
          "--library",
          "-f",
          "-o",
          implJar.toString,
          "--extra-jar",
          sigsJar.toString,
          "-color:never",
          "-language:experimental.captureChecking,experimental.separationChecking",
          tempImplementation.toString
        ),
        "Implementation.scala compilation"
      )
    finally deleteRecursively(tempDir)

  private def checkedUniverse(universe: String): String = {
    val trimmed = universe.trim
    if !trimmed.matches("[A-Za-z][A-Za-z0-9_-]*") then
      throw IllegalArgumentException(
        "universe must be a non-empty identifier using letters, digits, underscores, or hyphens"
      )
    trimmed
  }

  private def checkedApi(api: String): String =
    if api.exists(ch => ch == '\n' || ch == '\r') then
      throw IllegalArgumentException("manifest api must be a single-line calling convention")
    else if api.trim.isEmpty then throw IllegalArgumentException("manifest api must not be empty")
    else api

  private def checkedBindingName(bindingName: String): String = {
    val trimmed = bindingName.trim
    if !trimmed.matches("[a-z][A-Za-z0-9_]*") then
      throw IllegalArgumentException(
        "manifest binding name must be a lower-camel-case Scala identifier"
      )
    trimmed
  }

  private def checkedFqn(label: String, fqn: String): String = {
    val trimmed = fqn.trim
    if !trimmed.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+") then
      throw IllegalArgumentException(s"$label must be a fully-qualified Scala name")
    trimmed
  }

  private def checkedImplementationPackage(implementationPackage: String): String = {
    val pkg = checkedFqn("implementation package", implementationPackage)
    if !pkg.endsWith(".internal") then
      throw IllegalArgumentException("implementation package must end with `.internal`")
    pkg
  }

  private def scalaStringLiteral(value: String): String = {
    val body =
      value.flatMap {
        case '\\'                                           => "\\\\"
        case '"'                                            => "\\\""
        case '\n'                                           => "\\n"
        case '\r'                                           => "\\r"
        case '\t'                                           => "\\t"
        case char if java.lang.Character.isISOControl(char) => f"\\u${char.toInt}%04x"
        case char                                           => char.toString
      }
    "\"" + body + "\""
  }

  private def renderManifest(
      universe: String,
      bindingName: String,
      interfaceFqn: String,
      implementationPackage: String
  ): String = {
    val checkedBinding = checkedBindingName(bindingName)
    val checkedInterface = checkedFqn("interface FQN", interfaceFqn)
    val checkedImplPackage = checkedImplementationPackage(implementationPackage)
    val api = s"val $checkedBinding: $checkedInterface = ..."
    val predef = s"val $checkedBinding: $checkedInterface = $checkedImplPackage.impl"
    s"""val Manifest = (
       |  universe = ${scalaStringLiteral(checkedUniverse(universe))},
       |  api = ${scalaStringLiteral(checkedApi(api))},
       |  predef = ${scalaStringLiteral(predef)}
       |)
       |""".stripMargin
  }

  private def skillFile(skillName: String, relativeFile: String): JPath =
    checkedRelative(s"$skillName/$relativeFile")

  private def readRequiredSkillFile(skillName: String, relativeFile: String): String = {
    val file = skillFile(skillName, relativeFile)
    if !Files.exists(file, LinkOption.NOFOLLOW_LINKS) then
      throw IllegalArgumentException(
        s"`$relativeFile` must be written before this file can be validated"
      )
    Files.readString(checked(file), StandardCharsets.UTF_8)
  }

  private def checklistFor(skillName: String): stagedskills.SkillChecklist = {
    val written =
      requiredFiles.filter { file =>
        Files.exists(skillFile(skillName, file), LinkOption.NOFOLLOW_LINKS)
      }
    stagedskills.SkillChecklist(
      skill = skillName,
      written = written,
      remaining = requiredFiles.filterNot(written.toSet),
      complete = written.size == requiredFiles.size
    )
  }

  private def wrap(path: JPath): Path = Path(checked(path))

  private update def reserveFileWrite(relativeFile: String): Unit =
    fileWrittenInCall match
      case Some(previous) =>
        throw IllegalStateException(
          s"only one staged skill file may be written per run_scala_code call; `$previous` was already written. Read the returned checklist, then make another call before writing `$relativeFile`."
        )
      case None =>
        fileWrittenInCall = Some(relativeFile)

  private def ensureWritableFile(path: JPath): Unit = {
    val parent = Option(path.getParent).getOrElse(rootPath)
    Files.createDirectories(checked(parent))
    if Files.exists(path) && Files.isDirectory(path) then
      throw IllegalArgumentException(
        s"cannot write file over directory: ${rootPath.relativize(path)}"
      )
  }

  private def writeFile(path: JPath, content: String): Path = {
    val target = checked(path)
    ensureWritableFile(target)
    Files.writeString(
      target,
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
    wrap(target)
  }

  def root(): Path = wrap(rootPath)

  def path(relativePath: String): Path = wrap(checkedRelative(relativePath))

  def list(path: Path): Seq[Path] = {
    val target = checked(path.inner)
    if target == rootPath && !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then Seq.empty
    else {
      if !Files.isDirectory(target) then
        throw IllegalArgumentException(s"not a directory: ${rootPath.relativize(target)}")
      val stream = Files.list(target)
      try stream.iterator().asScala.map(wrap).toSeq.sortBy(_.toString)
      finally stream.close()
    }
  }

  def read(path: Path): String = Files.readString(checked(path.inner), StandardCharsets.UTF_8)

  update def write(path: Path, content: String): Path = {
    val target = checked(path.inner)
    reserveFileWrite(displayPath(target))
    writeFile(target, content)
  }

  def makeDirectories(path: Path): Path = {
    val target = checked(path.inner)
    Files.createDirectories(target)
    wrap(target)
  }

  def exists(path: Path): Boolean = Files.exists(checked(path.inner))

  def checklist(name: String): stagedskills.SkillChecklist =
    checklistFor(checkedSkillName(name))

  update def writeSkillMarkdown(name: String, skillMd: String): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    val content = checkedSkillMarkdown(skillMd)
    reserveFileWrite(s"$skillName/SKILL.md")
    writeFile(skillFile(skillName, "SKILL.md"), content)
    checklistFor(skillName)
  }

  update def writeInterface(name: String, interfaceScala: String): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    val content = checkedInterface(interfaceScala)
    validateInterfaceCompiles(content)
    reserveFileWrite(s"$skillName/scripts/Interface.scala")
    writeFile(skillFile(skillName, "scripts/Interface.scala"), content)
    checklistFor(skillName)
  }

  update def writeImplementation(
      name: String,
      implementationScala: String
  ): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    val content = checkedImplementation(implementationScala)
    val interfaceScala = readRequiredSkillFile(skillName, "scripts/Interface.scala")
    validateImplementationCompiles(interfaceScala, content)
    reserveFileWrite(s"$skillName/scripts/Implementation.scala")
    writeFile(skillFile(skillName, "scripts/Implementation.scala"), content)
    checklistFor(skillName)
  }

  update def writeManifest(
      name: String,
      universe: String,
      bindingName: String,
      interfaceFqn: String,
      implementationPackage: String
  ): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    val manifestPath = skillFile(skillName, "scripts/Manifest.scala")
    val content = renderManifest(universe, bindingName, interfaceFqn, implementationPackage)
    reserveFileWrite(s"$skillName/scripts/Manifest.scala")
    writeFile(manifestPath, content)
    checklistFor(skillName)
  }
}

private object StagedSkillWorkspaceAccessImpl extends stagedskills.StagedSkillWorkspaceAccess {
  def run[T](f: stagedskills.StagedSkillWorkspace^ => T): T =
    f(LocalStagedSkillWorkspace(CurrentSession.rootPath()))
}

@assumeSafe
val impl: stagedskills.StagedSkillWorkspaceAccess = StagedSkillWorkspaceAccessImpl
