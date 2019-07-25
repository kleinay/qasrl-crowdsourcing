package qasrl.crowd

import spacro.Assignment

import qasrl.crowd.util.implicits._
import akka.actor.{Actor, ActorRef}

import upickle.default._
import qasrl.crowd.util.implicits._
import qasrl.crowd.util.dollarsToCents

import spacro._
import spacro.tasks._
import spacro.util._

import com.typesafe.scalalogging.StrictLogging
import scala.util.{Try, Success, Failure}
import upickle.default.{Reader, Writer, read}

class QASDGenerationAggregationManager[SID : Reader : Writer](
  genAgreementActor: ActorRef,
  validationHelper: HITManager.Helper[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]],
  validationActor: ActorRef,
  numAssignmentsForPrompt: QASRLGenerationPrompt[SID] => Int,
  namingSuffix: String = ""
    )( implicit annotationDataService: AnnotationDataService
) extends Actor with StrictLogging {


  val genAssignmentAggregationStatusFilename = "generationAssignmentAggregation" + namingSuffix

  // internal state of the actor - the table of genHITId -> list of approved assignments
  var genApprovedAssingments =
    annotationDataService.loadLiveData (genAssignmentAggregationStatusFilename)
    .map (_.mkString)
    .map (read[Map[String, List[Assignment[QANomResponse]]]] )
    .toOption.getOrElse {
      Map.empty[String, List[Assignment[QANomResponse]]]
    }

  // save table of approved (=finished) genAssignments to file
  private[this] def save = {
    Try (
    annotationDataService.saveLiveData (
    genAssignmentAggregationStatusFilename,
    write[Map[String, List[Assignment[QANomResponse]]]] (genApprovedAssingments) )
    ).toOptionLogging (logger).foreach (_ => logger.info ("generation assignments aggregation data saved.") )
  }

  // handle generation hit whose assignments were all completed
  def handleCompletedGenHIT(hit: HIT[QASRLGenerationPrompt[SID]],
                            allAssignments: List[Assignment[QANomResponse]]) : Unit = {
    // aggregate assignments' ids and responses
    val genAssignmentIds = allAssignments.map(_.assignmentId)
    val allQANomResponses : List[QANomResponse] = allAssignments.map(_.response)
    val allQAsFromResponses : List[VerbQA] = allQANomResponses.map(_.qas).flatten  // take all qas from all respondents
    // generate validation prompt corresponding to generation HIT
    val validationPrompt = QASRLValidationPrompt (hit.prompt, hit.hitTypeId, hit.hitId, genAssignmentIds, allQANomResponses)
    validationActor ! validationHelper.Message.AddPrompt (validationPrompt)

    // when only single Gen worker, don't inform genAgreementActor
    if (allAssignments.size > 1) {
      // For each generator of the HIT, update QASRLGenerationAgreementManager
      // with all other genAssignments to compute agreement
      for (assignment <- allAssignments) {
        val otherWorkersAssignments = allAssignments.filter(_ != assignment)

        genAgreementActor ! QASRLGenHITFinished(assignment,
          assignment.response,
          otherWorkersAssignments.map(_.response))
      }
    }
  }

  // handle new approved generation assignment
  def handleApprovedGenAssignment (hit: HIT[QASRLGenerationPrompt[SID]],
                                   assignment: Assignment[QANomResponse] ) : Unit = {
    val genHITId = hit.hitId
    // add assignment to genApprovedAssingments map
    genApprovedAssingments.get (genHITId) match {
      // if genHITId not in map - add it with a List containing current assignment
      case None =>
        genApprovedAssingments = genApprovedAssingments.updated (genHITId, List (assignment) )
      // if it is in map - append assignment to list
      case Some (listOfAssignments) =>
        genApprovedAssingments = genApprovedAssingments.updated (genHITId, listOfAssignments ++ List (assignment) )
    }

    /*
       now check if genApprovedAssignments[genHITId] contain all required assignments for the HIT,
       and if so, call handleCompletedGenHIT, so it will:
        1) message the validationActor
        2) message QASRLGenerationAgreementManager
        */
    val allAssignments = genApprovedAssingments.get (genHITId).get
    val nAssignmentForThisGenHIT = allAssignments.size
    val requiredNumOfAssignments = numAssignmentsForPrompt (hit.prompt)

    if (nAssignmentForThisGenHIT >= requiredNumOfAssignments) {
      handleCompletedGenHIT(hit, allAssignments)
    }
  }

  override def receive = {
    case SaveData => save
    case aga: ApprovedGenAssignment[SID] => aga match {
      case ApprovedGenAssignment(genHIT, genAssignment) =>
        handleApprovedGenAssignment (genHIT, genAssignment)
    }
  }
}
