import example._
import qasrl.crowd._
import spacro._
import spacro.tasks._
import spacro.util._
import akka.pattern.ask
import scala.concurrent.duration._
import cats.implicits._

import com.amazonaws.services.mturk._
import com.amazonaws.services.mturk.model._

import nlpdata.util.Text
import nlpdata.util.HasTokens.ops._
import nlpdata.structure.Word

val label = "nom_training_"

val isProduction = true // sandbox. change to true for production
val domain = "u.cs.biu.ac.il/~stanovg/qasrl" // change to your domain, or keep localhost for testing
val projectName = "qasrl-crowd-example" // make sure it matches the SBT project;
// this is how the .js file is found to send to the server

val interface = "0.0.0.0"
val httpPort = 5903
val httpsPort = 5903

val annotationPath = java.nio.file.Paths.get(s"data/tqa/$label/annotations")
implicit val timeout = akka.util.Timeout(5.seconds)
implicit val config: TaskConfig = {
  if(isProduction) {
    val hitDataService = new FileSystemHITDataService(annotationPath.resolve("production"))
    ProductionTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
  } else {
    val hitDataService = new FileSystemHITDataService(annotationPath.resolve("sandbox"))
    SandboxTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
  }
}

val setup = new AnnotationSetup(label, Stage.Training)
import setup.SentenceIdHasTokens

val exp = setup.experiment
exp.server

def saveGenerationData(filename: String) = {
  val nonEmptyGens = exp.allGenInfos.filter(_.assignments.nonEmpty)
  setup.saveGenerationData(filename, nonEmptyGens)
}


def exit = {
  // actor system has to be terminated for JVM to be able to terminate properly upon :q
  config.actorSystem.terminate
  // flush & release logging resources
  import org.slf4j.LoggerFactory
  import ch.qos.logback.classic.LoggerContext
  LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].stop
  System.out.println("Terminated actor system and logging. Type :q to end.")
}

// use with caution... intended mainly for sandbox
def deleteAll = {
  exp.setGenHITsActiveEach(0)
  exp.setValHITsActive(0)
  Thread.sleep(200)
  exp.expire
  exp.delete
}

def yesterday = {
  val cal = java.util.Calendar.getInstance
  cal.add(java.util.Calendar.DATE, -1)
  cal.getTime
}

import scala.collection.JavaConverters._

def expireHITById(hitId: String) = {
  config.service.updateExpirationForHIT(
    (new UpdateExpirationForHITRequest)
      .withHITId(hitId)
      .withExpireAt(yesterday))
}

def approveAllAssignmentsByHITId(hitId: String) = for {
  mTurkAssignment <- config.service.listAssignmentsForHIT(
    new ListAssignmentsForHITRequest()
      .withHITId(hitId)
      .withAssignmentStatuses(AssignmentStatus.Submitted)
    ).getAssignments.asScala.toList
} yield config.service.approveAssignment(
  new ApproveAssignmentRequest()
    .withAssignmentId(mTurkAssignment.getAssignmentId)
    .withRequesterFeedback(""))

def deleteHITById(hitId: String) =
  config.service.deleteHIT((new DeleteHITRequest).withHITId(hitId))

def disableHITById(hitId: String) = {
  expireHITById(hitId)
  deleteHITById(hitId)
}

def getActiveHITIds = {
  config.service.listAllHITs.map(_.getHITId)
}

def getGenActiveHITTypeIds = {
  val generationTitlePrefix = "Write question-answer pairs"
  config.service.listAllHITs.filter(_.getTitle.startsWith(generationTitlePrefix)).map(_.getHITTypeId).toSet
}

def getValActiveHITTypeIds = {
  val validationTitlePrefix = "Answer simple questions"
  config.service.listAllHITs.filter(_.getTitle.startsWith(validationTitlePrefix)).map(_.getHITTypeId).toSet
}

def getActiveHITIdsOfHITType(hitTypeId : String) = {
  config.service.listAllHITs.filter(_.getHITTypeId.equals(hitTypeId)).map(_.getHITId)
}

def disableHITsOfTypeId(hitTypeId : String) = {
  getActiveHITIdsOfHITType(hitTypeId) map disableHITById
}

def getOurActiveHITIds = {
  (getGenActiveHITTypeIds ++ getValActiveHITTypeIds).map(getActiveHITIdsOfHITType).flatten
}

def disableAllOurHITs = {
  getOurActiveHITIds map disableHITById
}
