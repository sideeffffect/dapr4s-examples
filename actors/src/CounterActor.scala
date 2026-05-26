package actors

import dapr4s.*
import scala.concurrent.duration.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// ActorContext is a per-invocation capability provided fresh for every method
// call, reminder, and timer firing.  It cannot be stored between invocations:
// any `val ctx = summon[ActorContext]` attempt in a handler would be rejected
// by the compiler if the stored value could outlive the handler's scope.
//
// Actor state is accessed only through ActorContext.get / .set — there is no
// shared mutable field on the actor class itself.
// ─────────────────────────────────────────────────────────────────────────────

// ── Domain ────────────────────────────────────────────────────────────────────

case class IncrBy(amount: Int)
case class CounterState(count: Int, totalIncrements: Int)

@scala.caps.assumeSafe
object ActorDomain:
  given upickle.default.ReadWriter[IncrBy]       = upickle.default.macroRW
  given upickle.default.ReadWriter[CounterState] = upickle.default.macroRW

import ActorDomain.given

val ActorTypeName  = ActorType("CounterActor")
val StateKey_Count = StateKey("count")
val AutoTimer      = TimerName("auto-tick")
val ResetReminder  = ReminderName("scheduled-reset")

// ── Handler methods ───────────────────────────────────────────────────────────
// Pure logic; `import language.experimental.safe` enforces it at file level
// in a real project.  We omit it here only because @main uses println.

def readState(using ActorContext): CounterState =
  CounterState(
    count             = ActorContext.get[Int](StateKey_Count).getOrElse(0),
    totalIncrements   = ActorContext.get[Int](StateKey("total")).getOrElse(0),
  )

def increment(req: IncrBy)(using ActorContext): CounterState =
  val s = readState
  ActorContext.set(StateKey_Count, s.count + req.amount)
  ActorContext.set(StateKey("total"), s.totalIncrements + 1)
  readState

def reset()(using ActorContext): CounterState =
  ActorContext.set(StateKey_Count, 0)
  readState

def onAutoTick(req: IncrBy)(using ActorContext): Unit =
  increment(req)
  ()

def onReset(msg: String)(using ActorContext): Unit =
  println(s"[actor] scheduled reset triggered: $msg")
  reset()
  ()

// ── Actor definition ──────────────────────────────────────────────────────────

def counterActorDefinition: ActorDefinition =
  ActorDefinition(ActorTypeName): (id, ctx) =>
    given ActorContext = ctx
    ActorRoutes(
      methods = List(
        ActorMethodRoute[IncrBy, CounterState](MethodName("increment"))(increment),
        ActorMethodRoute[Unit, CounterState](MethodName("get"))(_ => readState),
        ActorMethodRoute[Unit, CounterState](MethodName("reset"))(_ => reset()),
        // Activate the auto-tick timer on first contact: every 3 s, increment by 1.
        ActorMethodRoute[Unit, CounterState](MethodName("startTimer")): _ =>
          ActorContext.registerTimer(AutoTimer, IncrBy(1), 3.seconds, Some(3.seconds))
          readState
        ,
        // Schedule a one-shot reminder (survives actor deactivation).
        ActorMethodRoute[Unit, CounterState](MethodName("scheduleReset")): _ =>
          ActorContext.registerReminder(ResetReminder, "time to reset", 30.seconds, None)
          readState
        ,
      ),
      timers = List(
        ActorTimerRoute[IncrBy](AutoTimer)(onAutoTick),
      ),
      reminders = List(
        ActorReminderRoute[String](ResetReminder)(onReset),
      ),
    )

// ── Actor server ──────────────────────────────────────────────────────────────

@main def actorApp(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8086").toInt
  val config = DaprRuntimeConfig(
    appServer = AppServerConfig(port = DaprPort(port)),
    actors    = ActorRuntimeConfig(
      actorIdleTimeout          = DaprDuration(10.minutes),
      drainOngoingCallTimeout   = DaprDuration(10.seconds),
    ),
  )
  println(s"=== 06 actors: CounterActor server on port $port ===\n")
  DaprRuntime.serve(config):
    DaprApp(actors = List(counterActorDefinition))
