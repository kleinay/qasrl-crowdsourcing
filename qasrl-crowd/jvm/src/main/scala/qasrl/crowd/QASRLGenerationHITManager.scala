package qasrl.crowd

import cats.implicits._

import spacro._
import spacro.tasks._

// import qamr.Pring
// import qamr.SaveData
// import qamr.AnnotationDataService

import scala.collection.mutable
import scala.util.{Try, Success, Failure}

import upickle.default.Reader

import akka.actor.ActorRef

import com.amazonaws.services.mturk.model.AssociateQualificationWithWorkerRequest

import upickle.default._

import com.typesafe.scalalogging.StrictLogging

case class FlagBadSentence[SID](id: SID)

class QASRLGenerationHITManager[SID : Reader : Writer](
  helper: HITManager.Helper[QASRLGenerationPrompt[SID], QANomResponse],
  validationHelper: HITManager.Helper[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]],
  validationActor: ActorRef,
  aggregationManager: ActorRef,
  accuracyManager: ActorRef, // of class QASRLGenerationAccuracyManager[SID],
  coverageDisqualificationTypeId: String,
  // sentenceTrackingActor: ActorRef,
  numAssignmentsForPrompt: QASRLGenerationPrompt[SID] => Int,
  numValidationAssignmentForPrompt: QASRLValidationPrompt[SID] => Int,
  initNumHITsToKeepActive: Int,
  _promptSource: Iterator[QASRLGenerationPrompt[SID]],
  settings: QASRLSettings = QASRLSettings.default,
  namingSuffix: String = "")(
  implicit annotationDataService: AnnotationDataService
) extends NumAssignmentsHITManager[QASRLGenerationPrompt[SID], QANomResponse](
  helper, numAssignmentsForPrompt, initNumHITsToKeepActive, _promptSource
) with StrictLogging {

  import helper._
  import config._
  import taskSpec.hitTypeId

  override def promptFinished(prompt: QASRLGenerationPrompt[SID]): Unit = {
    // sentenceTrackingActor ! GenerationFinished(prompt)
  }

  val badSentenceIdsFilename = "badSentenceIds"+ namingSuffix

  var badSentences = annotationDataService.loadLiveData(badSentenceIdsFilename)
    .map(_.mkString)
    .map(read[Set[SID]])
    .toOption.foldK

  private[this] def flagBadSentence(id: SID) = {
    badSentences = badSentences + id
    save
    for {
      (prompt, hitInfos) <- activeHITInfosByPromptIterator
      if prompt.id == id
      HITInfo(hit, _) <- hitInfos
    } yield helper.expireHIT(hit)
  }

  val coverageStatsFilename = "coverageStats" + namingSuffix

  var coverageStats: Map[String, List[Int]] = annotationDataService.loadLiveData(coverageStatsFilename)
    .map(_.mkString)
    .map(read[Map[String, List[Int]]])
    .toOption.getOrElse(Map.empty[String, List[Int]])

  def christenWorker(workerId: String, numQuestions: Int) = {
    val newStats = numQuestions :: coverageStats.get(workerId).getOrElse(Nil)
    coverageStats = coverageStats.updated(workerId, newStats)
    val newQuestionsPerVerb = newStats.sum.toDouble / newStats.size
  }

  val feedbackFilename = "genFeedback" + namingSuffix

  var feedbacks =
    annotationDataService.loadLiveData(feedbackFilename)
      .map(_.mkString)
      .map(read[List[Assignment[QANomResponse]]])
      .toOption.foldK

  private[this] def save = {
    annotationDataService.saveLiveData(
      coverageStatsFilename,
      write(coverageStats))
    annotationDataService.saveLiveData(
      feedbackFilename,
      write(feedbacks))
    annotationDataService.saveLiveData(
      badSentenceIdsFilename,
      write(badSentences))
    logger.info("Generation"+ namingSuffix+" data saved.")
  }

  override def reviewAssignment(hit: HIT[QASRLGenerationPrompt[SID]], assignment: Assignment[QANomResponse]): Unit = {
    evaluateAssignment(hit, startReviewing(assignment), Approval(""))
    if(!assignment.feedback.isEmpty) {
      feedbacks = assignment :: feedbacks
      logger.info(s"Feedback: ${assignment.feedback}")
    }


    // update coverage statistics
    if(assignment.response.isVerbal) {
      val newQuestionRecord = assignment.response.qas.size :: coverageStats.get(assignment.workerId).foldK
      coverageStats = coverageStats.updated(assignment.workerId, newQuestionRecord)
      val verbsCompleted = newQuestionRecord.size
      val questionsPerVerb = newQuestionRecord.sum.toDouble / verbsCompleted
      if (questionsPerVerb < settings.generationCoverageQuestionsPerVerbThreshold &&
        verbsCompleted > settings.generationCoverageGracePeriod) {
        config.service.associateQualificationWithWorker(
          new AssociateQualificationWithWorkerRequest()
            .withQualificationTypeId(coverageDisqualificationTypeId)
            .withWorkerId(assignment.workerId)
            .withIntegerValue(1)
            .withSendNotification(true))
      }
    }
    /*
     Aggregated Validation:
     Changed the creation of the validation prompt into a message to the QASDGenerationAggregationManager;
     It is it's responsibility to inform the validationActor
      */
    aggregationManager ! ApprovedGenAssignment(hit, assignment)

    // previous (non-aggregated)
//    val validationPrompt = QASRLValidationPrompt(hit.prompt, hit.hitTypeId, hit.hitId, assignment.assignmentId, assignment.response)
//    validationActor ! validationHelper.Message.AddPrompt(validationPrompt)

    // Grant Bonus (automatically) if no validators in pipeline
    val validationPrompt = QASRLValidationPrompt(hit.prompt, hit.hitTypeId, hit.hitId, List(assignment.assignmentId), List(assignment.response))
    // validationPrompt is not really going to be sent to validators. it is only for the message to accuracyManager
    val numValidators = numValidationAssignmentForPrompt(validationPrompt)
    if (numValidators == 0) {
      // grant bonus by sending genAccruacyManager a message that tells him as if a validation
      // HIT approved all his questions (it only grant bonus)
      val allQuestionsInGenAssignment = assignment.response.qas.map(_.question)
      accuracyManager ! QASRLValidationFinished(validationPrompt, allQuestionsInGenAssignment)
    }
  }

  override lazy val receiveAux2: PartialFunction[Any, Unit] = {
    case SaveData => save
    case Pring => println("Generation"+ namingSuffix+" manager pringed.")
    case fbs: FlagBadSentence[SID] => fbs match {
      case FlagBadSentence(id) => flagBadSentence(id)
    }
    case ChristenWorker(workerId, numQuestions) =>
      christenWorker(workerId, numQuestions)
  }
}
