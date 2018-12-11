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

// todo not complete conversion
class QAWSSDGenerationAgreementManager[SID : Reader : Writer](
 genDisqualificationTypeId: String)(
 implicit annotationDataService: AnnotationDataService,
 config: TaskConfig
) extends Actor with StrictLogging {

  import config._
  val settings = QASDSettings.default

  val workerStatsFilename = "WSSDgenerationWorkerAgreementStats"

  var allWorkerStats =
    annotationDataService.loadLiveData(workerStatsFilename)
      .map(_.mkString)
      .map(read[Map[String, QAWSSDGenerationWorkerStats]])
      .toOption.getOrElse {
      Map.empty[String, QAWSSDGenerationWorkerStats]
    }


  private[this] def save = {
    Try(
      annotationDataService.saveLiveData(
        workerStatsFilename,
        write[Map[String, QAWSSDGenerationWorkerStats]](allWorkerStats))
    ).toOptionLogging(logger).foreach(_ => logger.info("WSSD-Generators agreement stats data saved."))
  }

//  private def getAssignmentFromValPrompt(valPrompt: QASRLValidationPrompt[SID]): Option[Assignment[List[VerbQA]]] = {
//    val assignmentsForHIT = for {
//      hit <- hitDataService.getHIT[QASRLGenerationPrompt[SID]](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).toOptionLogging(logger).toList
//      assignment <- hitDataService.getAssignmentsForHIT[List[VerbQA]](valPrompt.sourceHITTypeId, valPrompt.sourceHITId).get
//    } yield assignment
//    assignmentsForHIT.find(_.assignmentId == valPrompt.sourceAssignmentId)
//  }

  def assessQualification(workerId: String): Unit = {
    Try {
      allWorkerStats.get(workerId).foreach { stats =>
        val workerIsDisqualified = config.service
          .listAllWorkersWithQualificationType(genDisqualificationTypeId)
          .contains(stats.workerId)

        val workerShouldBeDisqualified = !stats.genAgreementAccuracy.isNaN &&
          stats.genAgreementAccuracy < settings.generationAgreementBlockingThreshold &&
          stats.genAgreementJudgments.size.toFloat > settings.generationAgreementGracePeriod

        // Should change to Qualified
        if(workerIsDisqualified && !workerShouldBeDisqualified) {
          config.service.disassociateQualificationFromWorker(
            new DisassociateQualificationFromWorkerRequest()
              .withQualificationTypeId(genDisqualificationTypeId)
              .withWorkerId(stats.workerId)
              .withReason("Agreement dropped too low on the question writing on sentence task."))
          // Should change to Disqualified
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

  // todo verify
  def hasAgreement(qa: VerbQA, otherResponses: List[List[VerbQA]]) : Boolean = {
    // check whether any of the Answers given by worker to Q have any overlapping answer in otherResponses
    val otherAnswerSpans : List[Span] = otherResponses.flatten.flatMap(_.answers)
    def spanOverlap(span1: Span, span2: Span) : Boolean = {
      span1.contains(span2.begin) || span1.contains(span2.end)
    }
    // return:
    qa.answers.exists(
      ownAns => otherAnswerSpans.exists(otherAns => spanOverlap(ownAns, otherAns))
    )
  }

  override def receive = {
    case SaveData => save


    case QASRLGenHITFinished(assignment, response, otherResponses) => {
      // for a specific generators vs. the other generators
      val agreementJudgments : Vector[GenAgreementJudgment] = {
      // Judgment for each question
        for (qa <- response)
          yield GenAgreementJudgment(assignment.hitId, qa.question, hasAgreement(qa, otherResponses))
      }.toVector



      allWorkerStats = allWorkerStats.updated(
        assignment.workerId,
        allWorkerStats
          .get(assignment.workerId)
          .getOrElse(QASRLGenerationWorkerStats.empty(assignment.workerId))
          .addGenAgreementJudgments(agreementJudgments)
      )

      assessQualification(assignment.workerId)

    }

  }
}
