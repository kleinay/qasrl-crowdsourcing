package qasrl.crowd

import qasrl.crowd.util.dollarsToCents

import spacro._
import spacro.tasks._
import spacro.util._

import scala.collection.mutable
import scala.util.{Try, Success, Failure}

import upickle.default.Reader

import akka.actor.ActorRef

import com.amazonaws.services.mturk.model.AssociateQualificationWithWorkerRequest
import com.amazonaws.services.mturk.model.SendBonusRequest
import com.amazonaws.services.mturk.model.NotifyWorkersRequest
import com.amazonaws.services.mturk.model.CreateWorkerBlockRequest
import com.amazonaws.services.mturk.model.ListWorkersWithQualificationTypeRequest
import com.amazonaws.services.mturk.model.DisassociateQualificationFromWorkerRequest

import upickle.default._

import com.typesafe.scalalogging.StrictLogging

class QASRLEvaluationHITManager[SID : Reader : Writer](
  helper: HITManager.Helper[QASRLArbitrationPrompt[SID], QANomResponse],
  numAssignmentsForPrompt: QASRLArbitrationPrompt[SID] => Int,
  initNumHITsToKeepActive: Int,
  _promptSource: Iterator[QASRLArbitrationPrompt[SID]])(
  implicit annotationDataService: AnnotationDataService,
  settings: QASRLEvaluationSettings
) extends NumAssignmentsHITManager[QASRLArbitrationPrompt[SID], QANomResponse](
  helper, numAssignmentsForPrompt, initNumHITsToKeepActive, _promptSource, false) {

  override lazy val receiveAux2: PartialFunction[Any, Unit] = {
    case SaveData => save
    case Pring => println("Evaluation manager pringed.")
    case ChristenWorker(workerId, numAgreementsToAdd) => christenWorker(workerId, numAgreementsToAdd)
  }

  override def promptFinished(prompt: QASRLArbitrationPrompt[SID]): Unit = {}

  def christenWorker(workerId: String, numAgreementsToAdd: Int) = {
    allWorkerInfo = allWorkerInfo.get(workerId).fold(allWorkerInfo) { info =>
      val newInfo = info.addBonusAgreements(numAgreementsToAdd)
      allWorkerInfo.updated(workerId, newInfo)
    }
  }


  val workerInfoFilename = "evaluationWorkerInfo"

  var allWorkerInfo = {
    annotationDataService.loadLiveData(workerInfoFilename)
      .map(_.mkString)
      .map(read[Map[String, QASRLValidationWorkerInfo]])
      .toOption.getOrElse {
      Map.empty[String, QASRLValidationWorkerInfo]
    }
  }

  val feedbackFilename = "valFeedback"

  var feedbacks =
    annotationDataService.loadLiveData(feedbackFilename)
      .map(_.mkString)
      .map(read[List[Assignment[QANomResponse]]])
      .toOption
      .getOrElse(List.empty[Assignment[QANomResponse]])


  private[this] def save = {
    annotationDataService.saveLiveData(
      workerInfoFilename,
      write[Map[String, QASRLValidationWorkerInfo]](allWorkerInfo))
    annotationDataService.saveLiveData(
      feedbackFilename,
      write[List[Assignment[QANomResponse]]](feedbacks))
    logger.info("Evaluation data saved.")
  }

  override def reviewAssignment(hit: HIT[QASRLArbitrationPrompt[SID]], assignment: Assignment[QANomResponse]): Unit = {
    helper.evaluateAssignment(hit, helper.startReviewing(assignment), Approval(""))

    // save feedback
    if(!assignment.feedback.isEmpty) {
      feedbacks = assignment :: feedbacks
      logger.info(s"Feedback: ${assignment.feedback}")
    }

    // grant bonus as appropriate
    import assignment.workerId
    val numQuestions = hit.prompt.genResponses.size
    val totalBonus = settings.arbitrationBonus(numQuestions)
    if(totalBonus > 0.0) {
      helper.config.service.sendBonus(
        new SendBonusRequest()
          .withWorkerId(workerId)
          .withBonusAmount(f"$totalBonus%.2f")
          .withAssignmentId(assignment.assignmentId)
          .withReason(s"Bonus of ${dollarsToCents(totalBonus)}c awarded for validating $numQuestions questions.")
      )
    }

  }
}
