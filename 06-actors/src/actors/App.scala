package actors

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

case class IncrBy(amount: Int)
case class CounterState(count: Int, totalIncrements: Int)

val ActorTypeName = ActorType("CounterActor")
val StateKey_Count = ActorStateKey("count")
val StateKey_Total = ActorStateKey("total")
val AutoTimer = TimerName("auto-tick")
val ResetReminder = ReminderName("scheduled-reset")

// ── Capture-checked pure module ───────────────────────────────────────────────
// ActorContext is a per-invocation ExclusiveCapability: the compiler rejects
// any attempt to capture it in a value that outlives the handler scope.
// Actor state flows only through ActorContext.get / .set.
// Duration constants are passed from the shell.  All JsonCodec instances are
// passed from the shell so codec derivation and primitive codecs stay out of
// this module.
// ─────────────────────────────────────────────────────────────────────────────

// ── Actor handler methods ─────────────────────────────────────────────────────

def readState(using ActorContext, JsonCodec[Int]): CounterState =
  CounterState(
    count = ActorContext.get[Int](StateKey_Count).getOrElse(0),
    totalIncrements = ActorContext.get[Int](StateKey_Total).getOrElse(0),
  )

def increment(req: IncrBy)(using ActorContext, JsonCodec[Int]): CounterState =
  val s = readState
  ActorContext.set(StateKey_Count, s.count + req.amount)
  ActorContext.set(StateKey_Total, s.totalIncrements + 1)
  readState

def reset()(using ActorContext, JsonCodec[Int]): CounterState =
  ActorContext.set(StateKey_Count, 0)
  readState

def onAutoTick(req: IncrBy)(using ActorContext, JsonCodec[Int]): Unit =
  increment(req)
  ()

def onReset(msg: String)(using ActorContext, JsonCodec[Int]): Unit =
  reset()
  ()

// ── Actor definition ──────────────────────────────────────────────────────────

def counterActorDefinition(
    tickInterval: FiniteDuration,
    tickDelay: Option[FiniteDuration],
    reminderDelay: FiniteDuration,
)(using
    JsonCodec[IncrBy],
    JsonCodec[CounterState],
    JsonCodec[Int],
    JsonCodec[Unit],
    JsonCodec[String],
): ActorDefinition =
  ActorDefinition(ActorTypeName): _ =>
    ActorRoutes(
      methods = List(
        ActorMethodRoute[IncrBy, CounterState](ActorMethodName("increment"))(increment),
        ActorMethodRoute[Unit, CounterState](ActorMethodName("get"))(_ => readState),
        ActorMethodRoute[Unit, CounterState](ActorMethodName("reset"))(_ => reset()),
        ActorMethodRoute[Unit, CounterState](ActorMethodName("startTimer")): _ =>
          ActorContext.registerTimer(AutoTimer, IncrBy(1), tickInterval, tickDelay)
          readState
        ,
        ActorMethodRoute[Unit, CounterState](ActorMethodName("scheduleReset")): _ =>
          ActorContext.registerReminder(ResetReminder, "time to reset", reminderDelay, None)
          readState,
      ),
      timers = List(ActorTimerRoute[IncrBy](AutoTimer)(onAutoTick)),
      reminders = List(ActorReminderRoute[String](ResetReminder)(onReset)),
    )

object CounterActorApp:
  def apply(
      tickInterval: FiniteDuration,
      tickDelay: Option[FiniteDuration],
      reminderDelay: FiniteDuration,
  )(using JsonCodec[IncrBy], JsonCodec[CounterState], JsonCodec[Int], JsonCodec[Unit], JsonCodec[String]): DaprApp =
    DaprApp(actors = List(counterActorDefinition(tickInterval, tickDelay, reminderDelay)))

// ── Driver helper methods ─────────────────────────────────────────────────────

val DemoActorId = ActorId("counter-1")

def driverGetState(id: ActorId)(using DaprCapability, JsonCodec[CounterState]): CounterState =
  DaprCapability.actor(ActorTypeName, id):
    ActorCapability.invoke[CounterState](ActorMethodName("get"))

def driverIncrement(id: ActorId, by: IncrBy)(using
    DaprCapability,
    JsonCodec[IncrBy],
    JsonCodec[CounterState],
): CounterState =
  DaprCapability.actor(ActorTypeName, id):
    ActorCapability.invoke[IncrBy](ActorMethodName("increment"), by)[CounterState]

def driverStartTimer(id: ActorId)(using DaprCapability, JsonCodec[CounterState]): CounterState =
  DaprCapability.actor(ActorTypeName, id):
    ActorCapability.invoke[CounterState](ActorMethodName("startTimer"))
