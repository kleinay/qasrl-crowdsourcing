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

val label = "nom_dev_18.8.19"

val isProduction = false // sandbox. change to true for production
val domain = "u.cs.biu.ac.il/~stanovg/qasrl" // change to your domain, or keep localhost for testing
val projectName = "qasrl-crowd-example" // make sure it matches the SBT project;
// this is how the .js file is found to send to the server

val interface = "0.0.0.0"
val httpPort = 5901
val httpsPort = 5901

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

def exit = {
  // actor system has to be terminated for JVM to be able to terminate properly upon :q
  config.actorSystem.terminate
  // flush & release logging resources
  import org.slf4j.LoggerFactory
  import ch.qos.logback.classic.LoggerContext
  LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].stop
  System.out.println("Terminated actor system and logging. Type :q to end.")
}

val setup = new AnnotationSetup(label, executePreprocessing=true)
import setup.SentenceIdHasTokens

val exp = setup.experiment
exp.server

// save source sentences
def saveSourceSentences(sentences : Vector[String] ) : Unit = {
  import io.circe.Json
  val jsn=Json.fromValues(sentences.map(Json.fromString))
  import java.io.{OutputStreamWriter, File, FileOutputStream}
  import java.nio.charset.StandardCharsets
  val writer = new OutputStreamWriter(
    new FileOutputStream(s"data/tqa/$label/sourceSentences.json"), StandardCharsets.UTF_8)
  //      PrintWriter(new File(s"data/tqa/$label/tokenizedSentences.json"))
  writer.write(jsn.toString)
  writer.close()
}

// save tokenized sentences
def saveTokenizedIds(sentences : Vector[Vector[String]] ) : Unit = {
  import io.circe.Json
  def sent2json(sentence : Vector[String]) : Json = Json.fromValues(sentence.map(Json.fromString))
  val jsn=Json.fromValues(sentences.map(sent2json))
  import java.io.{OutputStreamWriter, File, FileOutputStream}
  import java.nio.charset.StandardCharsets
  val writer = new OutputStreamWriter(
    new FileOutputStream(s"data/tqa/$label/tokenizedSentences.json"), StandardCharsets.UTF_8)
//      PrintWriter(new File(s"data/tqa/$label/tokenizedSentences.json"))
  writer.write(jsn.toString)
  writer.close()
}

def savePOSTaggedSentences(sentences : Vector[Vector[Word]]) : Unit = {
  import io.circe.Json
  def word2json(word : Word) : Json = Json.fromFields(List(
    ("index", Json.fromInt(word.index)),
    ("pos", Json.fromString(word.pos)),
    ("token", Json.fromString(word.token))
  ))
  def posSent2json(posSent : Vector[Word]) : Json = Json.fromValues(posSent.map(word2json))
  val jsn = Json.fromValues(sentences.map(posSent2json))
  import java.io.{OutputStreamWriter, File, FileOutputStream}
  import java.nio.charset.StandardCharsets
  val writer = new OutputStreamWriter(
    new FileOutputStream(s"data/tqa/$label/posTaggedSentences.json"), StandardCharsets.UTF_8)
//    new PrintWriter(new File(s"data/tqa/$label/posTaggedSentences.json"))
  writer.write(jsn.toString)
  writer.close()
}

saveSourceSentences(setup.sentences)
saveTokenizedIds(setup.tokenizedSentences)
//savePOSTaggedSentences(setup.posTaggedSentences)


def saveGenerationData(filename: String) = {
  val nonEmptyGens = exp.allGenInfos.filter(_.assignments.nonEmpty)
  setup.saveGenerationData(filename, nonEmptyGens)
}

// use with caution... intended mainly for sandbox
def deleteAll = {
  exp.setGenHITsActiveEach(0)
  exp.setSDGenHITsActiveEach(0)
  exp.setValHITsActive(0)
  exp.setSDValHITsActive(0)
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

def costOfQASD(verbsPrompts : Int, sdPrompts : Int, isValAggregated : Boolean = true) : Double = {
  val avgGenQAPerNonVerb = 1.3
  val avgGenQAPerVerb = 2.3
  val numGenerators = setup.numGenerationAssignmentsInProduction
  val numValidators = 0 // todo change manually
  // generation cost computation
  val verbGenAssignments = verbsPrompts * numGenerators
  val sdGenAssignments = sdPrompts * numGenerators
  val verbGenQAs = verbGenAssignments * avgGenQAPerVerb
  val sdGenQAs = sdGenAssignments * avgGenQAPerNonVerb
  // for simplicity, we'll compute as if every generated question is granted the same
  val verbGenCost = QASRLSettings.default.generationReward * verbGenQAs
  val sdGenCost = QASDSettings.default.generationReward * sdGenQAs
  val genTotalCost = verbGenCost + sdGenCost

  // validation cost computation
  val valTotalCost = if(isValAggregated){
    // When aggregated validation:
    val verbValAssignments = verbsPrompts * numValidators
    val sdValAssignments = sdPrompts * numValidators
    val verbAvgQsPerTarget = avgGenQAPerVerb * numGenerators
    val sdAvgQsPerTarget = avgGenQAPerNonVerb * numGenerators
    // how much should one validator be paid for one assignments?
    val verbValAvgAssignmentPayment = verbAvgQsPerTarget * QASRLSettings.default.validationBonusPerQuestion
    val sdValAvgAssignmentPayment = sdAvgQsPerTarget * QASDSettings.default.validationBonusPerQuestion
    val verbValCost = verbValAvgAssignmentPayment * verbValAssignments
    val sdValCost = sdValAvgAssignmentPayment * sdValAssignments
    verbValCost + sdValCost
  } else {
    // When validation is not aggregated, i.e. each generators has its own validators
    val verbValAssignments = verbGenAssignments * numValidators
    val sdValAssignments = sdGenAssignments * numValidators
    val verbAvgQsPerTarget = avgGenQAPerVerb
    val sdAvgQsPerTarget = avgGenQAPerNonVerb
    // how much should one validator be paid for one assignments?
    val verbValAvgAssignmentPayment = QASRLSettings.default.validationReward +
      math.max(0, (verbAvgQsPerTarget-QASRLSettings.default.validationBonusThreshold) * QASRLSettings.default.validationBonusPerQuestion)
    val sdValAvgAssignmentPayment = QASDSettings.default.validationReward +
      math.max(0, (verbAvgQsPerTarget-QASDSettings.default.validationBonusThreshold) * QASDSettings.default.validationBonusPerQuestion)
    val verbValCost = verbValAvgAssignmentPayment * verbValAssignments
    val sdValCost = sdValAvgAssignmentPayment * sdValAssignments
    verbValCost + sdValCost
  }
  // final cost
  genTotalCost + valTotalCost
}

def currentPipelineCost(isValAggregated : Boolean = true) : Double = {
  costOfQASD(exp.allVerbPrompts.size, exp.allSDPrompts.size, isValAggregated)
}
