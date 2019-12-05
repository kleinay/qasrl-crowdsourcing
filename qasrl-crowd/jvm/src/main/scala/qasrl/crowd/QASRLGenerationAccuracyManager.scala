package qasrl.crowd

import qasrl.crowd.util.implicits._
import qasrl.crowd.util.dollarsToCents

import spacro._
import spacro.tasks._
import spacro.util._

import scala.collection.mutable
import scala.util.{Try, Success, Failure}

import akka.actor.{Actor, ActorRef}

import com.amazonaws.services.mturk.model.AssignmentStatus
import com.amazonaws.services.mturk.model.HITStatus
import com.amazonaws.services.mturk.model.SendBonusRequest
import com.amazonaws.services.mturk.model.NotifyWorkersRequest
import com.amazonaws.services.mturk.model.AssociateQualificationWithWorkerRequest
import com.amazonaws.services.mturk.model.DisassociateQualificationFromWorkerRequest

import upickle.default._

import com.typesafe.scalalogging.StrictLogging

class QASRLGenerationAccuracyManager[SID : Reader : Writer](
  genDisqualificationTypeId: String)(
  implicit annotationDataService: AnnotationDataService,
  config: TaskConfig,
  settings: QASRLSettings
) extends Actor with StrictLogging {

  import config._

  val workerStatsFilename = "generationWorkerAccuracyStats" // keep two files, this one is for accuracy (i.e. validation)

  var allWorkerStats =
    annotationDataService.loadLiveData(workerStatsFilename)
      .map(_.mkString)
      .map(read[Map[String, QASRLGenerationWorkerStats]])
      .toOption.getOrElse {
      Map.empty[String, QASRLGenerationWorkerStats]
    }

  def christenWorker(workerId: String, numAgreementsToAdd: Int) = {
    allWorkerStats = allWorkerStats.get(workerId).fold(allWorkerStats) { stats =>
      allWorkerStats.updated(workerId, stats.addBonusValids(numAgreementsToAdd))
    }
    assessQualification(workerId)
  }

  private[this] def save = {
    Try(
      annotationDataService.saveLiveData(
        workerStatsFilename,
        write[Map[String, QASRLGenerationWorkerStats]](allWorkerStats))
    ).toOptionLogging(logger).foreach(_ => logger.info("Worker stats data saved."))
  }

  private def getGenAssignmentsFromValPrompt(valPrompt: QASRLValidationPrompt[SID]): List[Assignment[QANomResponse]] = {
    val assignmentsForHIT = for {
      hit <- hitDataService.getHIT[QASRLGenerationPrompt[SID]](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).toOptionLogging(logger).toList
      assignment <- hitDataService.getAssignmentsForHIT[QANomResponse](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).get
    } yield assignment
    assignmentsForHIT.filter(asmnt => valPrompt.sourceAssignmentId.contains(asmnt.assignmentId))
  }

  def assessQualification(workerId: String): Unit = {
    Try {
      allWorkerStats.get(workerId).foreach { stats =>
        val workerIsDisqualified = config.service
          .listAllWorkersWithQualificationType(genDisqualificationTypeId)
          .contains(stats.workerId)

        val workerShouldBeDisqualified = !stats.accuracy.isNaN &&
          stats.accuracy < settings.generationAccuracyBlockingThreshold &&
          (stats.numValidatorJudgments / 2) > settings.generationAccuracyGracePeriod

        if(workerIsDisqualified && !workerShouldBeDisqualified) {
          config.service.disassociateQualificationFromWorker(
            new DisassociateQualificationFromWorkerRequest()
              .withQualificationTypeId(genDisqualificationTypeId)
              .withWorkerId(stats.workerId)
              .withReason("Accuracy dropped too low on the question writing task."))
        } else if(!workerIsDisqualified && workerShouldBeDisqualified) {
          config.service.associateQualificationWithWorker(
            new AssociateQualificationWithWorkerRequest()
              .withQualificationTypeId(genDisqualificationTypeId)
              .withWorkerId(stats.workerId)
              .withIntegerValue(1)
              .withSendNotification(true))
        }
      }
    }
  }

  override def receive = {
    case SaveData => save
    case ChristenWorker(workerId, numAgreementsToAdd) => christenWorker(workerId, numAgreementsToAdd)
    case ValidatorBlocked(badValidatorId) =>
      allWorkerStats = allWorkerStats.map {
        case (wid, stats) => wid -> stats.removeJudgmentsByWorker(badValidatorId)
      }
      allWorkerStats.keys.foreach(assessQualification)
    case vr: QASRLValidationResult[SID] => vr match {
      case QASRLValidationResult(valPrompt, valWorker, valResponse) =>
        getGenAssignmentsFromValPrompt(valPrompt).foreach { assignment =>
          val accuracyJudgments = valResponse.map(r => AccuracyJudgment(valWorker, r.isAnswer)).toVector

          allWorkerStats = allWorkerStats.updated(
            assignment.workerId,
            allWorkerStats
              .get(assignment.workerId)
              .getOrElse(QASRLGenerationWorkerStats.empty(assignment.workerId))
              .addAccuracyJudgments(accuracyJudgments)
          )

          assessQualification(assignment.workerId)
        }
    }
  }
}
