package scangateway

import dapr4s.*
import scala.scalajs.js

// ── Impure shell (Scala.js) ─────────────────────────────────────────────────
// The Scala.js twin of the JVM 12-scan-gateway shell. The pure module (App.scala)
// is shared verbatim — only the entry point differs: `serve` suspends the Wasm
// stack via JSPI, so the single `js.async { ... }` at the program edge satisfies
// the requirement documented on `dapr4s.Dapr`. Codecs (upickle, not capture-aware,
// hence @assumeSafe) and the env-driven config are otherwise identical to the JVM.
// ─────────────────────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[ScanRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[SubmitResponse] = upickleCodec(using upickle.default.macroRW)

import Codecs.given

// Read a port from Node's process.env (the Scala.js analogue of sys.env), falling
// back to the canonical Dapr defaults the e2e compose also sets.
private def envInt(name: String, default: Int): Int =
  val v = js.Dynamic.global.process.env.selectDynamic(name)
  if js.isUndefined(v) || v == null then default else v.toString.toIntOption.getOrElse(default)

private def daprConfigFromEnv(defaultAppPort: Int): DaprConfig =
  DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:${envInt("DAPR_HTTP_PORT", 3500)}"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:${envInt("DAPR_GRPC_PORT", 50001)}"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(envInt("APP_PORT", defaultAppPort))),
  )

@main def scanGateway(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8080)
  println(s"=== 15 scan-pipeline (Scala.js): gateway on port ${config.appServer.port} ===")
  js.async {
    Dapr(config).serve:
      GatewayApp()
  }: Unit
