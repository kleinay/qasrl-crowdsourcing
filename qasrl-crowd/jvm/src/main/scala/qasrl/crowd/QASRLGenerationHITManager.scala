package qasrl.crowd

import cats.implicits._

import com.amazonaws.services.mturk.model.{SendBonusRequest, SendBonusResult}
import qasrl.crowd.util.implicits._
import qasrl.crowd.util.dollarsToCents
import spacro._
import spacro.util.RichTry
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

  def grantBonusForGenerator(genAssignment: Assignment[QANomResponse]) : Unit = {
    // award bonus for the worker of the generation assignment, for its valid questions
    val numQAsProvided = genAssignment.response.qas.size
    // count how many of generated questions are valid (according to validators)
    val bonusAwarded = settings.generationBonus(numQAsProvided)
    val bonusCents = dollarsToCents(bonusAwarded)
    if(bonusAwarded > 0.0) {
      Try(
        service.sendBonus(
          new SendBonusRequest()
            .withWorkerId(genAssignment.workerId)
            .withBonusAmount(f"$bonusAwarded%.2f")
            .withAssignmentId(genAssignment.assignmentId)
            .withReason(
              s"""You have generated $numQAsProvided question-answer pairs, for a bonus of ${bonusCents}c."""))
      ).toOptionLogging(logger).ifEmpty(logger.error(s"Failed to grant bonus of $bonusCents to worker ${genAssignment.workerId}"))
    }
  }
  val f: Option[Int] = Some(3)
  f.ifEmpty()

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

    // Grant Bonus (automatically) if no validators in pipeline
    grantBonusForGenerator(assignment)
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
