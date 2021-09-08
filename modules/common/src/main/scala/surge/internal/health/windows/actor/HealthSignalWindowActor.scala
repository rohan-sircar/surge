// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.internal.health.windows.actor

import akka.actor.{ Actor, ActorRef, ActorSystem, Cancellable, PoisonPill, Props, Stash }
import akka.pattern.{ ask, BackoffOpts, BackoffSupervisor }
import org.slf4j.{ Logger, LoggerFactory }
import surge.health.HealthSignalBusTrait
import surge.health.domain.{ HealthSignal, HealthSignalSource }
import surge.health.matchers.{ SideEffect, SideEffectBuilder, SignalPatternMatchResult, SignalPatternMatcherDefinition }
import surge.health.windows._
import surge.internal.config.{ BackoffConfig, TimeoutConfig }
import surge.internal.health.windows._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.languageFeature.postfixOps

case class WindowState(window: Option[Window] = None, replyTo: Option[ActorRef] = None)

object HealthSignalWindowActor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  trait Control {}
  case class Start(window: Window, replyTo: ActorRef) extends Control
  case class Tick() extends Control
  case class Stop() extends Control
  case class Flush() extends Control

  case class Pause(duration: FiniteDuration) extends Control
  case class Resume() extends Control

  def apply(
      actorSystem: ActorSystem,
      initialWindowProcessingDelay: FiniteDuration,
      resumeWindowProcessingDelay: FiniteDuration,
      windowFrequency: FiniteDuration,
      advancer: Advancer[Window],
      signalPatternMatcherDefinition: SignalPatternMatcherDefinition,
      signalBus: HealthSignalBusTrait,
      windowCheckInterval: FiniteDuration = 1.second): HealthSignalWindowActorRef = {

    // note: we lose the window data on restarts
    val props = BackoffSupervisor.props(
      BackoffOpts
        .onFailure(
          Props(new HealthSignalWindowActor(windowFrequency, resumeWindowProcessingDelay, signalPatternMatcherDefinition, signalBus, advancer)),
          childName = "healthSignalWindowActor",
          minBackoff = BackoffConfig.HealthSignalWindowActor.minBackoff,
          maxBackoff = BackoffConfig.HealthSignalWindowActor.maxBackoff,
          randomFactor = BackoffConfig.HealthSignalWindowActor.randomFactor)
        .withMaxNrOfRetries(BackoffConfig.HealthSignalWindowActor.maxRetries))

    val windowActor = actorSystem.actorOf(props)
    new HealthSignalWindowActorRef(windowActor, initialWindowProcessingDelay, windowFrequency, actorSystem, windowCheckInterval)
  }
}

class HealthSignalWindowActorRef(
    val actor: ActorRef,
    initialWindowProcessingDelay: FiniteDuration,
    windowFreq: FiniteDuration,
    actorSystem: ActorSystem,
    tickInterval: FiniteDuration = 1.second) {
  import HealthSignalWindowActor._

  private var listener: WindowStreamListeningActorRef = _

  private val scheduledTask: Cancellable =
    actorSystem.scheduler.scheduleAtFixedRate(initialDelay = initialWindowProcessingDelay, interval = tickInterval)(() => actor ! Tick())(
      ExecutionContext.global)

  def start(replyTo: Option[ActorRef]): HealthSignalWindowActorRef = {
    val window: Window = Window.windowFor(Instant.now(), windowFreq, control = Some(actor))
    // Listener of Window Streaming events generated by the HealthSignalWindowActor
    listener = WindowStreamListeningActorAdapter(actorSystem, replyTo)
    actor ! Start(window, listener.actor)
    this
  }

  def tick(): HealthSignalWindowActorRef = {
    actor ! Tick()
    this
  }

  def flush(): HealthSignalWindowActorRef = {
    actor ! Flush()
    this
  }

  def pause(duration: FiniteDuration): HealthSignalWindowActorRef = {
    actor ! Pause(duration)
    this
  }

  def stop(): HealthSignalWindowActorRef = {
    actor ! Stop()
    scheduledTask.cancel()
    this
  }

  def closeWindow(): HealthSignalWindowActorRef = {
    actor ! CloseCurrentWindow()
    this
  }

  def terminate(): Unit = {
    actor ! PoisonPill
  }

  def processSignal(signal: HealthSignal): HealthSignalWindowActorRef = {
    actor ! signal
    this
  }

  def windowSnapshot(): Future[Option[WindowSnapShot]] = {
    actor.ask(GetWindowSnapShot())(TimeoutConfig.HealthWindow.actorAskTimeout).mapTo[Option[WindowSnapShot]]
  }
}

/**
 * HealthSignalWindowActor is responsible for managing a Window of HealthSignal data and determining how and when to advance the window using a provided
 * windowAdvanceStrategy Advancer.
 *
 * All HealthSignal(s) received when the Window has been opened via OpenWindow command; are appended to the Window. When a Window is closed via CloseWindow
 * command; HealthSignal(s) that have accumulated in the Window are delivered in a WindowClosed event. When a Window is advanced via AdvanceWindow command;
 * HealthSignal(s) that have accumulated in the Window are delivered in a WindowAdvanced event.
 *
 * @param frequency
 *   FiniteDuration
 * @param windowAdvanceStrategy
 *   Advancer
 */
class HealthSignalWindowActor(
    frequency: FiniteDuration,
    resumeWindowProcessingDelay: FiniteDuration,
    signalPatternMatcherDefinition: SignalPatternMatcherDefinition,
    signalBus: HealthSignalBusTrait,
    windowAdvanceStrategy: Advancer[Window])
    extends Actor
    with Stash {
  import HealthSignalWindowActor._

  private val matcher = signalPatternMatcherDefinition.toMatcher

  override def receive: Receive = initializing

  /**
   * Initialization
   * @return
   *   Receive
   */
  def initializing: Receive = {
    case Start(window, replyTo) =>
      HealthSignalWindowActor.log.trace("Starting window {}", window)
      unstashAll()
      context.self ! OpenWindow(window, None)
      context.become(ready(WindowState().copy(replyTo = Some(replyTo))))
    case Stop() =>
      context.stop(self)
    case HealthSignal => stash()
  }

  /**
   * Ready to handle a Window
   * @param state
   *   WindowState
   * @return
   *   Receive
   */
  def ready(state: WindowState): Receive = {
    case AdvanceWindow(w, n) =>
      reportWindowAdvanced(w, n, state)
      val advancedWindowState = advanceWindow(w, n, state)
      context.self ! OpenWindow(n, None)
      context.become(ready(advancedWindowState.copy()))
    case signal: HealthSignal =>
      log.debug(s"Got signal $signal while in ready state.")
      stash()
    case OpenWindow(w, maybeSignal) =>
      val openedWindowState = openWindow(w, maybeSignal, state)
      unstashAll()
      context.become(windowing(openedWindowState.copy()))
    case GetWindowSnapShot() =>
      sender ! WindowSnapShot(data = state.window.map(w => w.snapShotData()).getOrElse(Seq.empty))
    case Stop() => stop(state)
    case _      => stash()
  }

  /**
   * Processing active Window
   * @param state
   *   WindowState
   * @return
   *   Receive
   */
  // scalastyle:off cyclomatic.complexity
  def windowing(state: WindowState): Receive = {
    case signal: HealthSignal =>
      state.window.foreach(w => context.self ! AddToWindow(signal, w))
    case AdvanceWindow(w, n) =>
      context.become(windowing(state = advanceWindow(w, n, state).copy()))
    case AddToWindow(signal: HealthSignal, window) =>
      context.become(windowing(addToWindow(window, signal, state)))
    case CloseWindow(window, advance) =>
      context.become(ready(closeWindow(window, advance, state).copy()))
    case Flush() =>
      context.become(windowing(flush(state).copy()))
    case Pause(duration) =>
      context.become(pausing(pause(duration, state).copy()))
    case CloseCurrentWindow =>
      state.window.foreach(w => context.self ! CloseWindow(w, advance = true))
    case GetWindowSnapShot() =>
      sender ! state.window.map(w => WindowSnapShot(w.snapShotData()))
    case Stop() => stop(state)
    case Tick() =>
      tick(state)
  }

  def pausing(state: WindowState): Receive = {
    case GetWindowSnapShot() =>
      sender ! state.window.map(w => WindowSnapShot(w.snapShotData()))
    case _: HealthSignal =>
      stash()
    case Stop() => stop(state)
    case Resume() =>
      state.window.foreach { w =>
        state.replyTo.foreach(a => {
          HealthSignalWindowActor.log.trace("Notifying {} that window resumed", a)
          a ! WindowResumed(w)
        })
      }
      context.become(windowing(state.copy()))
  }

  private def flush(state: WindowState): WindowState = {
    val flushedWindow = state.window.flatMap(w => Some(w.copy(data = Seq.empty)))
    context.self ! Pause(duration = resumeWindowProcessingDelay)
    state.copy(window = flushedWindow)
  }

  private def pause(duration: FiniteDuration, state: WindowState): WindowState = {
    state.window.foreach { w =>
      state.replyTo.foreach(a => {
        HealthSignalWindowActor.log.trace("Notifying {} that window closed", a)
        a ! WindowPaused(w)
      })
    }
    context.system.scheduler.scheduleOnce(duration) {
      context.self ! Resume()
    }(context.dispatcher)

    state.copy()
  }

  private def stop(state: WindowState): WindowState = {
    state.replyTo.foreach(a => {
      HealthSignalWindowActor.log.trace("Notifying {} that windowing stopped", a)
      a ! WindowStopped(state.window)
    })

    state.window.foreach { w =>
      state.replyTo.foreach(a => {
        HealthSignalWindowActor.log.trace("Notifying {} that window closed", a)
        a ! WindowClosed(w, WindowData(state.window.map(w => w.data).getOrElse(Seq.empty), frequency))
      })
    }
    context.stop(self)

    state.copy()
  }

  private def addToWindow(window: Window, signal: HealthSignal, state: WindowState): WindowState = {
    val updatedWindow = window.copy(data = window.data ++ Seq(signal))
    advanceWindowCommand(updatedWindow).foreach(cmd => {
      context.self ! cmd
    })
    state.replyTo.foreach(r => {
      HealthSignalWindowActor.log.trace("Notifying {} that Health Signal was added to window", r)
      r ! AddedToWindow(signal, window)
    })

    state.copy(window = Some(updatedWindow))
  }

  private def advanceWindow(window: Window, newWindow: Window, state: WindowState): WindowState = {
    HealthSignalWindowActor.log.trace("Window advanced {}", window)
    reportWindowAdvanced(window, newWindow, state)
    processWindowForPatternMatches(window)
    state.copy(window = Some(newWindow.copy(priorData = window.data)))
  }

  private def closeWindow(window: Window, advance: Boolean, state: WindowState): WindowState = {
    val capturedSignals = state.window.map(w => w.data).getOrElse(Seq.empty)
    HealthSignalWindowActor.log.trace("Closing window {} and informing {}", Seq(window, state.replyTo): _*)
    state.replyTo.foreach(r => {
      HealthSignalWindowActor.log.trace("Notifying {} that window closed", r)
      r ! WindowClosed(window, WindowData(capturedSignals, frequency))
    })
    val nextState = state.copy(window = None)

    if (advance) {
      // Advance Window on Close
      HealthSignalWindowActor.log.trace("Checking if advance should occur")
      advanceWindowCommand(window, force = true).foreach(cmd => context.self ! cmd)
    } else {
      processWindowForPatternMatches(window)
    }

    nextState
  }

  protected def tick(state: WindowState): WindowState = {
    state.window.foreach(w => {
      if (w.expired()) {
        context.self ! CloseWindow(w, advance = true)
      }
    })

    state.copy()
  }

  private def openWindow(window: Window, maybeSignal: Option[HealthSignal], state: WindowState): WindowState = {
    val nextState = state.copy(window = Some(window.copy()))

    maybeSignal.foreach(s => context.self ! s)

    HealthSignalWindowActor.log.trace("Window opened {}", window)
    state.replyTo.foreach(r => {
      HealthSignalWindowActor.log.trace("Notifying {} that window opened", r)
      r ! WindowOpened(window)
    })

    nextState
  }

  private def advanceWindowCommand(window: Window, force: Boolean = false): Option[AdvanceWindow] = {
    val maybeAdvanced: Option[Window] = windowAdvanceStrategy.advance(window, force)
    HealthSignalWindowActor.log.trace("Possible Window Advance => {}", maybeAdvanced)
    maybeAdvanced.map(next => AdvanceWindow(window, next))
  }

  private def reportWindowAdvanced(oldWindow: Window, newWindow: Window, state: WindowState): Unit = {
    state.replyTo.foreach(r => {
      HealthSignalWindowActor.log.trace("Notifying {} that window has advanced", r)
      r ! WindowAdvanced(newWindow, WindowData(oldWindow.data, frequency))
    })
  }

  private def processWindowForPatternMatches(it: Window): Unit = {
    HealthSignalWindowActor.log.trace(s"Window tumbled $it")
    val all = matcher.searchForMatch(signalSource(it, it.data), it.duration)
    val freq = it.duration

    val result = SignalPatternMatchResult(all.matches, it.data, SideEffect(Seq()), freq, Some(it))

    val sideEffectBuilder = SideEffectBuilder()
    Option(all).foreach(r => {
      r.sideEffect.signals.foreach(s => {
        sideEffectBuilder.addSideEffectSignal(s)
      })
    })

    injectSignalPatternMatchResultIntoStream(result.copy(sideEffect = sideEffectBuilder.buildSideEffect()))
  }

  private def signalSource(window: Window, data: Seq[HealthSignal]): HealthSignalSource = {
    window.copy(data = data)
  }

  def injectSignalPatternMatchResultIntoStream(result: SignalPatternMatchResult): Unit = {
    result.sideEffect.signals.foreach(s => {
      signalBus.publish(s.copy(source = result.signalSource))
    })
  }
}