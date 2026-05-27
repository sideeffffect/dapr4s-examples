package actors

import dapr4s.*
import scala.concurrent.duration.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
object Codecs:
  given upickle.default.ReadWriter[IncrBy]       = upickle.default.macroRW
  given upickle.default.ReadWriter[CounterState] = upickle.default.macroRW

import Codecs.given

@main def actorApp(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8086").toInt
  val config = DaprRuntimeConfig(
    appServer = AppServerConfig(port = DaprPort(port)),
    actors    = ActorRuntimeConfig(
      actorIdleTimeout        = DaprDuration(10.minutes),
      drainOngoingCallTimeout = DaprDuration(10.seconds),
    ),
  )
  println(s"=== 06 actors: CounterActor server on port $port ===\n")
  DaprRuntime.serve(config):
    counterActorApp(tickInterval = 3.seconds, tickDelay = Some(3.seconds), reminderDelay = 30.seconds)

@main def actorDriver(): Unit =
  println("=== 06 actors: CounterActor driver ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    println(s"initial state: ${driverGetState(DemoActorId)}")

    for i <- 1 to 3 do
      println(s"after increment($i): ${driverIncrement(DemoActorId, IncrBy(i))}")
      Thread.sleep(300)

    println(s"timer started, state: ${driverStartTimer(DemoActorId)}")
    println("Waiting 10 s for timer ticks...")
    Thread.sleep(10_000)

    println(s"final state: ${driverGetState(DemoActorId)}")

  println("\n[actor-driver] done.")
