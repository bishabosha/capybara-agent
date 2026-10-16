package capybara.agent

import java.net.URLClassLoader
import scala.util.control.NonFatal
import scalanotation.Readers
import steps.result.Result

type PluginManifest = (entrypoint: String, classpath: Seq[String])

/** Owns plugin state and class loaders for one interactive session. */
final class HarnessPlugins private (
    val plugins: Vector[HarnessPlugin],
    resources: Vector[AutoCloseable]
) extends AutoCloseable:
  private val registered = plugins.flatMap(p => p.commands.map(c => (c.name, p.id, c)))
  private val names = registered.map(_._1)
  require(plugins.map(_.id).distinct.size == plugins.size, "Duplicate plugin id")
  require(plugins.forall(_.id.matches("[a-z][a-z0-9-]*")), "Invalid plugin id")
  require(names.distinct.size == names.size, "Duplicate plugin command")
  require(names.forall(_.matches("[a-z][a-z0-9-]*")), "Invalid plugin command name")
  require(!names.exists(HarnessPlugins.Reserved), "Plugin command conflicts with a built-in command")

  private var currentUi = composeUi().fold(error => throw IllegalArgumentException(error), identity)

  def ui: TerminalUi = synchronized { currentUi }

  /** Reconfigure after plugin commands. Invalid replacement views leave the old UI intact. */
  def refreshUi(): Either[String, TerminalUi] = synchronized {
    composeUi().map { replacement =>
      currentUi = replacement
      replacement
    }
  }

  private def composeUi(): Either[String, TerminalUi] =
    plugins.foldLeft[Either[String, TerminalUi]](Right(TerminalUi())) { (result, plugin) =>
      result.flatMap(current => attempt(plugin.id) {
        val next = plugin.configureUi(current)
        next.validate()
        next
      })
    }

  def commandNames: Vector[String] = names.map("/" + _)

  def help: String = registered.map { (name, owner, command) =>
    s"/$name — ${command.description} [$owner]"
  }.mkString("\n")

  def describe: String = plugins.map(p => s"${p.id} — ${p.description}").mkString("\n")

  def dispatch(line: String, context: PluginContext): Option[Either[String, PluginAction]] = synchronized {
    val parts = line.trim.drop(1).split("\\s+", 2)
    registered.find(_._1 == parts.head).map { (_, owner, command) =>
      attempt(owner)(command.run(parts.lift(1).getOrElse(""), context))
    }
  }

  def transform(prompt: String, context: PluginContext): Either[String, String] = synchronized {
    plugins.foldLeft[Either[String, String]](Right(prompt)) { (result, plugin) =>
      result.flatMap(text => attempt(plugin.id) {
        val transformed = plugin.transformPrompt(text, context)
        require(transformed != null && transformed.trim.nonEmpty, "Prompt must not be empty")
        transformed
      })
    }
  }

  /** One broken observer must not prevent other observers or session cleanup. */
  def emit(event: PluginEvent, context: PluginContext): Vector[String] = synchronized {
    plugins.flatMap(p => attempt(p.id)(p.onEvent(event, context)).left.toOption)
  }

  def close(): Unit = synchronized {
    (plugins.reverse ++ resources.reverse).foreach { resource =>
      try resource.close()
      catch case NonFatal(_) => ()
    }
  }

  private def attempt[A](owner: String)(body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(error) => Left(s"plugin $owner: ${error.getMessage}")

object HarnessPlugins:
  val Reserved: Set[String] = Set("x", "usage", "sys-prompt", "think", "help", "plugins", "reload", "clear", "export")

  def apply(plugins: Vector[HarnessPlugin]): HarnessPlugins = new HarnessPlugins(plugins, Vector.empty)

  def parseManifest(content: String): Result[PluginManifest, String] =
    Readers.readDeclAs[PluginManifest](content, rootName = "Manifest")
      .mapErr(error => s"plugin manifest parse error: ${error.format}")

  /** Source directories are loaded only when explicitly selected with --plugin-dir.
    * Each child contains a Scala Object Notation Manifest.scala and plugin sources.
    * Compile in a separate JVM so diagnostics never corrupt the interactive prompt.
    */
  def load(directory: Option[os.Path]): Either[String, HarnessPlugins] =
    val plugins = scala.collection.mutable.ArrayBuffer[HarnessPlugin](WorkflowPlugin())
    val resources = scala.collection.mutable.ArrayBuffer.empty[AutoCloseable]
    try
      directory.foreach { root =>
        require(os.isDir(root), s"Plugin directory does not exist: $root")
        os.list(root).filter(os.isDir(_)).sortBy(_.last).foreach { dir =>
          val manifestFile = dir / "Manifest.scala"
          val manifest = parseManifest(os.read(manifestFile)) match
            case Result.Ok(value) => value
            case Result.Err(error) => throw IllegalArgumentException(s"$manifestFile: $error")
          val entry = manifest.entrypoint.trim
          require(entry.nonEmpty, s"Missing entrypoint in $manifestFile")
          val libraries = manifest.classpath.map { configured =>
            require(configured.nonEmpty, s"Empty classpath entry in $manifestFile")
            val jar = dir / os.RelPath(configured)
            require(os.isFile(jar) && jar.ext == "jar", s"Plugin classpath JAR does not exist: $jar")
            jar
          }.distinct.toVector
          val sources = os.walk(dir).filter(p => os.isFile(p) && p.ext == "scala" && p != manifestFile).sortBy(_.toString)
          require(sources.nonEmpty, s"No Scala sources in $dir")
          val output = os.temp.dir(prefix = "capybara-plugin-")
          resources += new AutoCloseable:
            def close(): Unit = os.remove.all(output)
          val classpath = sys.props("java.class.path")
          val pluginClasspath = (Vector(classpath) ++ libraries.map(_.toString)).mkString(java.io.File.pathSeparator)
          val javaExecutable = os.Path(sys.props("java.home")) / "bin/java"
          val compiled = os.proc(Seq(javaExecutable.toString, "-cp", classpath, "dotty.tools.dotc.Main",
            "-classpath", pluginClasspath, "-d", output.toString) ++ sources.map(_.toString))
            .call(check = false, stderr = os.Pipe)
          require(compiled.exitCode == 0,
            s"Could not compile plugin $dir:\n${compiled.out.text()}${compiled.err.text()}")
          // Parent-first keeps the host API and Scala runtime shared. Conflicting libraries
          // must be shaded in plugin JARs; siblings never see one another's library URLs.
          val loader = new URLClassLoader((Vector(output) ++ libraries).map(_.toNIO.toUri.toURL).toArray,
            classOf[HarnessPlugin].getClassLoader)
          resources += loader
          val plugin = loader.loadClass(entry).asSubclass(classOf[HarnessPlugin]).getDeclaredConstructor().newInstance()
          plugins += plugin
        }
      }
      Right(new HarnessPlugins(plugins.toVector, resources.toVector))
    catch
      case NonFatal(error) =>
        (plugins.reverse ++ resources.reverse).foreach { resource =>
          try resource.close()
          catch case NonFatal(_) => ()
        }
        Left(s"Could not load plugins: ${error.getMessage}")
