package capybara.agent

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import steps.result.Result

class OllamaFailureSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.global

  test("transport errors expose the underlying timeout to the caller and UI") {
    val output = new OllamaClient.ChunkOutput[String]()
    val error = new java.io.IOException("Exception when sending request: POST http://localhost:11434/api/chat",
      new java.net.http.HttpTimeoutException("request timed out"))
    val request = OllamaClient.ChunkRequest.local(output, CancellationToken.Never) { throw error }
    val result = Await.result(request.send(), 3.seconds)
    result match
      case Result.Err(message) =>
        assert(message.contains("HttpTimeoutException: request timed out"))
        assert(message.contains("localhost:11434/api/chat"))
      case _ => fail("Expected request failure")
    assert(output.failed())
  }
