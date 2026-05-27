package e2e

import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.time.Duration

/** Thin wrapper around Java's HttpClient for calling the Dapr sidecar HTTP API. */
object DaprHttp:
  private val client = JHttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private def withTimeout(b: HttpRequest.Builder) =
    b.timeout(Duration.ofSeconds(30))

  def get(daprPort: Int, path: String): (Int, String) =
    val req = withTimeout(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$daprPort$path"))
        .GET()
        .header("Content-Type", "application/json")
    ).build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    (resp.statusCode(), resp.body())

  def post(daprPort: Int, path: String, body: String = ""): (Int, String) =
    val req = withTimeout(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$daprPort$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
    ).build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    (resp.statusCode(), resp.body())

  def put(daprPort: Int, path: String, body: String = ""): (Int, String) =
    val req = withTimeout(
      HttpRequest.newBuilder(URI.create(s"http://localhost:$daprPort$path"))
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
    ).build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    (resp.statusCode(), resp.body())

  /** Direct call to the app's own HTTP port (bypasses Dapr routing). */
  def appGet(appPort: Int, path: String): (Int, String) =
    get(appPort, path)

  def appPost(appPort: Int, path: String, body: String = ""): (Int, String) =
    post(appPort, path, body)
