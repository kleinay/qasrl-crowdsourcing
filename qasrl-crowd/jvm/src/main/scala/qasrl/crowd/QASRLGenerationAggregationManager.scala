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

class QASRLGenerationAggregationManager[SID : Reader : Writer](
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
    .map (read[Map[String, List[Assignment[List[VerbQA]]]]] )
    .toOption.getOrElse {
      Map.empty[String, List[Assignment[List[VerbQA]]]]
    }

  // save table of approved (=finished) genAssignments to file
  private[this] def save = {
    Try (
    annotationDataService.saveLiveData (
    genAssignmentAggregationStatusFilename,
    write[Map[String, List[Assignment[List[VerbQA]]]]] (genApprovedAssingments) )
    ).toOptionLogging (logger).foreach (_ => logger.info ("generation assignments aggregation data saved.") )
  }

  // handle generation hit whose assignments were all completed
  def handleCompletedGenHIT(hit: HIT[QASRLGenerationPrompt[SID]],
                            allAssignments: List[Assignment[List[VerbQA]]]) : Unit = {
    // aggregate assignments' ids and responses
    val genAssignmentIds = allAssignments.map(_.assignmentId)
    val allQAResponses = allAssignments.map(_.response).flatten
    // generate validation prompt corresponding to generation HIT
    val validationPrompt = QASRLValidationPrompt (hit.prompt, hit.hitTypeId, hit.hitId, genAssignmentIds, allQAResponses)
    validationActor ! validationHelper.Message.AddPrompt (validationPrompt)

    // todo: agreement computation should change place. not supporting genAgreement for verbs anymore.
    // when only single Gen worker, don't inform genAgreementActor
    if (allAssignments.size > 1) {
      // For each generator of the HIT, update QASRLGenerationAgreementManager
      // with all other genAssignments to compute agreement
      for (assignment <- allAssignments) {
        val otherWorkersAssignments = allAssignments.filter(_ != assignment)

        genAgreementActor ! QAWSSDGenHITFinished(assignment,
          assignment.response,
          otherWorkersAssignments.map(_.response))
      }
    }
  }

  // handle new approved generation assignment
  def handleApprovedGenAssignment (hit: HIT[QASRLGenerationPrompt[SID]],
                                   assignment: Assignment[List[VerbQA]] ) : Unit = {
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
    case aga: ApprovedVerbGenAssignment[SID] => aga match {
      case ApprovedVerbGenAssignment(genHIT, genAssignment) =>
        handleApprovedGenAssignment (genHIT, genAssignment)
    }
  }
}
