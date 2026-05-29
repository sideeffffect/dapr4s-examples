package actors

import dapr4s.*
import scala.concurrent.duration.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
object Codecs:
  given upickle.default.ReadWriter[IncrBy] = upickle.default.macroRW
  given upickle.default.ReadWriter[CounterState] = upickle.default.macroRW

import Codecs.given

private def daprConfigFromEnv(defaultAppPort: Int): DaprRuntimeConfig =
  val appPort = sys.env.getOrElse("APP_PORT", defaultAppPort.toString).toInt
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprRuntimeConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(appPort)),
    actors = ActorRuntimeConfig(
      actorIdleTimeout = DaprDuration(10.minutes),
      drainOngoingCallTimeout = DaprDuration(10.seconds),
    ),
  )

@main def actorApp(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8086)
  println(s"=== 06 actors: CounterActor server on port ${config.appServer.port} ===\n")
  DaprRuntime.serve(config):
    counterActorApp(tickInterval = 3.seconds, tickDelay = Some(3.seconds), reminderDelay = 30.seconds)

@main def actorDriver(): Unit =
  println("=== 06 actors: CounterActor driver ===\n")
  DaprRuntime.run(daprConfigFromEnv(defaultAppPort = 8086)):
    println(s"initial state: ${driverGetState(DemoActorId)}")

    for i <- 1 to 3 do
      println(s"after increment($i): ${driverIncrement(DemoActorId, IncrBy(i))}")
      Thread.sleep(300)

    println(s"timer started, state: ${driverStartTimer(DemoActorId)}")
    println("Waiting 10 s for timer ticks...")
    Thread.sleep(10_000)

    println(s"final state: ${driverGetState(DemoActorId)}")

  println("\n[actor-driver] done.")
