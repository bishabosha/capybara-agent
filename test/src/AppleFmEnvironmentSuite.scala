package capybara.agent

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

class AppleFmEnvironmentSuite extends munit.FunSuite:
  private val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
  private given ExecutionContext = ExecutionContext.fromExecutor(executor)
  override def afterAll(): Unit = { val _ = executor.shutdownNow() }
  private def inCache(test: os.Path => Unit): Unit =
    val cache = os.temp.dir(prefix = "capybara-apple-env-test-")
    try test(cache)
    finally os.remove.all(cache)

  private def fakeSetup(commands: scala.collection.mutable.Buffer[Seq[String]])(command: Seq[String], token: CancellationToken): Unit =
    commands.synchronized { val _ = commands += command }
    if command.contains("venv") then
      val environment = os.Path(command.last)
      os.makeDir.all(environment / "bin")
      os.write(environment / "bin/python", "fixture")
      require((environment / "bin/python").toIO.setExecutable(true))

  test("first use installs the pinned SDK, then reuses the persistent environment") {
    inCache { cache =>
      val commands = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      val first = AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), fakeSetup(commands))
      assertEquals(commands.size, 3)
      assert(commands(1).last == s"apple-fm-sdk==${AppleFmEnvironment.SdkVersion}")
      val second = AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), fakeSetup(commands))
      assertEquals(second, first)
      assertEquals(commands.size, 3)
    }
  }

  test("failed setup is retried and never marks a partial install ready") {
    inCache { cache =>
      val commands = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      val _ = intercept[IllegalStateException] {
        AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), (cmd, token) =>
          fakeSetup(commands)(cmd, token)
          if cmd.contains("pip") then throw IllegalStateException("download failed"))
      }
      assert(!os.exists(cache / s"sdk-${AppleFmEnvironment.SdkVersion}" / ".capybara-ready"))
      val _ = AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), fakeSetup(commands))
      assertEquals(commands.count(_.contains("venv")), 2)
    }
  }

  test("concurrent requests install once and a cancelled waiter can leave the setup lock") {
    inCache { cache =>
      val commands = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val setup = Future(AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), (cmd, token) =>
        fakeSetup(commands)(cmd, token)
        if cmd.contains("venv") then
          entered.countDown()
          require(release.await(5, java.util.concurrent.TimeUnit.SECONDS))))
      assert(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), s"Setup future: ${setup.value}; commands: ${commands.toVector}")
      val token = CancellationToken()
      val cancelled = Future(AppleFmEnvironment.ensure(cache, token, _ => (), fakeSetup(commands)))
      token.cancel()
      val _ = intercept[IllegalStateException](Await.result(cancelled, 2.seconds))
      val second = Future(AppleFmEnvironment.ensure(cache, CancellationToken.Never, _ => (), fakeSetup(commands)))
      release.countDown()
      assertEquals(Await.result(setup, 3.seconds), Await.result(second, 3.seconds))
      assertEquals(commands.size, 3)
    }
  }
