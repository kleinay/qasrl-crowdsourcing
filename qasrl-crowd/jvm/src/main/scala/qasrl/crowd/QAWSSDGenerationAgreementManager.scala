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

  // changed to fit WSSD pipeline - otherResponses are not necessarily of the same target!
  def hasAgreement(qa: VerbQA, otherResponses: List[List[VerbQA]]) : Boolean = {
    /*
    Algorithm: return true if:
     1. any of the Answers given by worker to Q
    have any overlapping answer in otherResponses to the same target;
    Or:
    2. any of the Answers given by worker to Q contains a target word,
    which has a QA whose answers include the original qa.verbIndex

     */
    def otherResponsesForIndex(i: Int) = otherResponses.flatten.filter(_.verbIndex == i)
    val otherResponsesToSameTarget = otherResponsesForIndex(qa.verbIndex)
    val otherAnswerSpans : List[Span] = otherResponsesToSameTarget.flatMap(_.answers)
    def spanOverlap(span1: Span, span2: Span) : Boolean = {
      span1.contains(span2.begin) || span1.contains(span2.end)
    }
    def spanIndices(span : Span) : Set[Int] = (span.begin until span.end+1).toSet
    // for 2., compute inverseQAs = all VerbQAs in otherResponses that are targeting words from qa.answers
    val qaAnswersIndices = qa.answers.flatMap(spanIndices).toSet - qa.verbIndex
    val inverseQAs = qaAnswersIndices.flatMap(otherResponsesForIndex)
    // return:
    qa.answers.exists(  // 1.
      ownAns => otherAnswerSpans.exists(otherAns => spanOverlap(ownAns, otherAns))
    ) || // 2.
    inverseQAs.flatMap(_.answers.flatMap(spanIndices)).contains(qa.verbIndex)
  }

  override def receive = {
    case SaveData => save


    case QAWSSDGenHITFinished(assignment, response, otherResponses) => {
      // for a specific generators vs. the other generators
      val agreementJudgments : Vector[WSSDGenAgreementJudgment] = {
      // Judgment for each question
        for (qa <- response)
          yield WSSDGenAgreementJudgment(assignment.hitId, qa.verbIndex, qa.question, hasAgreement(qa, otherResponses))
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
