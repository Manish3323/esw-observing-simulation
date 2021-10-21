package iris.detector

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import csw.command.client.CommandResponseManager
import csw.event.api.scaladsl.EventPublisher
import csw.framework.models.CswContext
import csw.params.commands.CommandResponse.Completed
import csw.params.events.IRDetectorEvent
import iris.detector.commands.{FitsData, FitsMessage}
import iris.detector.commands.FitsMessage._
import nom.tam.fits.{Fits, FitsFactory}
import nom.tam.util.BufferedFile

class FitsActor(cswContext: CswContext, config: Config) {
  val crm: CommandResponseManager    = cswContext.commandResponseManager
  val eventPublisher: EventPublisher = cswContext.eventService.defaultPublisher

  def setup: Behavior[FitsMessage] = Behaviors.receiveMessage { case WriteData(runId, data, exposureId, filename) =>
    val prefix = cswContext.componentInfo.prefix
    eventPublisher.publish(IRDetectorEvent.dataWriteStart(prefix, exposureId, filename))
    writeData(data, filename)
    crm.updateCommand(Completed(runId))
    eventPublisher.publish(IRDetectorEvent.dataWriteEnd(prefix, exposureId, filename))
    Behaviors.same
  }

  private def writeData(data: FitsData, filename: String): Unit = {
    if (config.getBoolean("writeDataToFile")) {
      val fits = new Fits()
      fits.addHDU(FitsFactory.hduFactory(data.data))
      val bf = new BufferedFile(filename, "rw")
      fits.write(bf)
      bf.close()
    }
  }
}