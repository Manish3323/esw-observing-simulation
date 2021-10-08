package iris.imageradc

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import csw.framework.models.CswContext
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.commands.CommandResponse.{Accepted, Completed, Invalid}
import csw.params.events.SystemEvent
import csw.time.core.models.UTCTime
import iris.imageradc.commands.PrismCommands.{GOING_IN, GOING_OUT}
import iris.imageradc.commands.{ADCCommand, PrismCommands}
import iris.imageradc.events.{PrismCurrentEvent, PrismRetractEvent, PrismStateEvent, PrismTargetEvent}
import iris.imageradc.models.PrismState.MOVING
import iris.imageradc.models.{PrismPosition, PrismState}

import scala.compat.java8.DurationConverters.FiniteDurationops
import scala.concurrent.duration.DurationInt
import scala.math.BigDecimal.RoundingMode

class PrismActor(cswContext: CswContext) {
  var prismTarget: BigDecimal      = 0.0
  var prismCurrent: BigDecimal     = 0.0
  var fastMoving: Boolean          = false
  private val timeServiceScheduler = cswContext.timeServiceScheduler
  private val crm                  = cswContext.commandResponseManager
  private lazy val eventPublisher  = cswContext.eventService.defaultPublisher

  def inAndStopped: Behavior[PrismCommands] = {
    publishPrismState(PrismState.STOPPED)
    publishRetractPosition(PrismPosition.IN)
    Behaviors.receive { (ctx, msg) =>
      {
        msg match {
          case PrismCommands.RETRACT_SELECT(runId, position) =>
            position match {
              case PrismPosition.IN =>
                crm.updateCommand(Completed(runId))
                Behaviors.same
              case PrismPosition.OUT =>
                println("RECEIVED PrismPosition.OUT")
                timeServiceScheduler.scheduleOnce(UTCTime(UTCTime.now().value.plus(4.seconds.toJava))) {
                  crm.updateCommand(Completed(runId))
                  ctx.self ! GOING_OUT
                }
                Behaviors.same
            }
          case PrismCommands.IS_VALID(runId, _, replyTo) =>
            replyTo ! Accepted(runId)
            Behaviors.same
          case PrismCommands.PRISM_FOLLOW(_, targetAngle) =>
            prismTarget = targetAngle
            inAndMoving
          case PrismCommands.PRISM_STOP(_) =>
            Behaviors.same
          case GOING_OUT =>
            println("GOING_OUT")
            outAndStopped
          case _ => Behaviors.unhandled
        }
      }
    }
  }
  def outAndStopped: Behavior[PrismCommands] = {
    publishRetractPosition(PrismPosition.OUT)
    Behaviors.receive { (ctx, msg) =>
      {
        val log = cswContext.loggerFactory.getLogger(ctx)
        msg match {
          case PrismCommands.RETRACT_SELECT(runId, position) =>
            position match {
              case PrismPosition.IN =>
                println("RECEIVED PrismPosition.IN")
                timeServiceScheduler.scheduleOnce(UTCTime(UTCTime.now().value.plus(4.seconds.toJava))) {
                  crm.updateCommand(Completed(runId))
                  ctx.self ! GOING_IN
                }
                Behaviors.same
              case PrismPosition.OUT =>
                crm.updateCommand(Completed(runId))
                Behaviors.same
            }
          case PrismCommands.IS_VALID(runId, command, replyTo) =>
            command.commandName match {
              case ADCCommand.RetractSelect =>
                replyTo ! Accepted(runId)
                Behaviors.same
              case cmd =>
                val errMsg = s"Setup command: ${cmd.name} is not valid in disabled state."
                log.error(errMsg)
                replyTo ! Invalid(runId, UnsupportedCommandIssue(errMsg))
                Behaviors.unhandled
            }
          case GOING_IN =>
            println("GOING_IN")
            inAndStopped
          case cmd =>
            val errMsg = s"$cmd is not valid in disabled state."
            log.error(errMsg)
            Behaviors.unhandled

        }
      }
    }
  }
  def inAndMoving: Behavior[PrismCommands] = {
    Behaviors.setup { ctx =>
      println("in here going to fast state >>>>>>>>>>>>>>>>>>>>>")
      fastMoving = true
      val fastMovement: BigDecimal = truncateTo1Decimal((prismTarget - prismCurrent) / 3)
      val targetModifier           = scheduleJobForPrismMovement(ctx)
      Behaviors.receiveMessage { msg =>
        val log = cswContext.loggerFactory.getLogger(ctx)
        msg match {
          case cmd @ PrismCommands.RETRACT_SELECT(_, _) =>
            val errMsg = s"$cmd is not valid in moving state."
            log.error(errMsg)
            Behaviors.unhandled
          case PrismCommands.IS_VALID(runId, command, replyTo) =>
            command.commandName match {
              case ADCCommand.RetractSelect =>
                val errMsg = s"Setup command: ${command.commandName.name} is not valid in moving state."
                log.error(errMsg)
                replyTo ! Invalid(runId, UnsupportedCommandIssue(errMsg))
                Behaviors.unhandled
              case _ =>
                replyTo ! Accepted(runId)
                Behaviors.same
            }
          case cmd @ PrismCommands.PRISM_FOLLOW(_, _) =>
            //TODD as if required schedule ??
            val errMsg = s"$cmd is not valid in moving state."
            log.error(errMsg)
            Behaviors.unhandled
          case PrismCommands.PRISM_STOP(_) =>
            targetModifier.cancel()
            inAndStopped
          case PrismCommands.MOVE_CURRENT =>
            println(s"==============MOVE CURRENT===================== $fastMoving")
            if (fastMoving)
              prismCurrent += fastMovement
            else
              prismCurrent += slowMovement
            publishEvent(PrismCurrentEvent.make(prismCurrent.toDouble, getCurrentDiff.toDouble))
            Behaviors.same
          case PrismCommands.MOVE_TARGET =>
            println(s"---------------MOVE_TARGET------------------:${getCurrentDiff.abs.compare(0.5)}")
            if (isWithinToleranceRange) {
              prismTarget += 0.1
              fastMoving = false
            }
            ctx.self ! PrismCommands.MOVE_CURRENT
            publishEvent(PrismTargetEvent.make(prismTarget.toDouble))
            publishPrismState(MOVING)
            Behaviors.same
        }
      }
    }

  }

  private def slowMovement = if (prismTarget > prismCurrent) +0.1 else -0.1

  private def isWithinToleranceRange = getCurrentDiff.abs.compare(0.5) == -1

  private def publishEvent(event: SystemEvent) = eventPublisher.publish(event)

  private def publishPrismState(state: PrismState) = eventPublisher.publish(getPrismStateEvent(state))

  private def getPrismStateEvent(state: PrismState) =
    PrismStateEvent.make(state, isWithinToleranceRange)

  private def publishRetractPosition(position: PrismPosition): Unit = {
    eventPublisher.publish(PrismRetractEvent.make(position))
  }

  private def scheduleJobForPrismMovement(ctx: ActorContext[PrismCommands]) = {
    timeServiceScheduler.schedulePeriodically(1.seconds.toJava) {
      ctx.self ! PrismCommands.MOVE_TARGET
    }
  }
  private def truncateTo1Decimal(value: BigDecimal) = BigDecimal(value.toDouble).setScale(1, RoundingMode.DOWN)

  private def getCurrentDiff = prismCurrent - prismTarget
}

object PrismActor {

  def behavior(cswContext: CswContext): Behavior[PrismCommands] =
    new PrismActor(cswContext).outAndStopped

}
