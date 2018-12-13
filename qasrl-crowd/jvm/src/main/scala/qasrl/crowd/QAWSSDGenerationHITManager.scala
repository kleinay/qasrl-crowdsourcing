package qasrl.crowd

import cats.implicits._
import spacro._
import spacro.tasks._
import upickle.default.Reader
import akka.actor.ActorRef
import com.amazonaws.services.mturk.model.AssociateQualificationWithWorkerRequest
import upickle.default._
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

// todo is this class (and Actor) needed? should we keep some coverage info for wssd workers?
class QAWSSDGenerationHITManager[SID : Reader : Writer](
  helper: HITManager.Helper[QAWSSDGenerationPrompt[SID], List[VerbQA]],
  agreementAccuracyActor: ActorRef, // of class QASRLGenerationAccuracyManager[SID],
  coverageDisqualificationTypeId: String,
  numAssignmentsForPrompt: QAWSSDGenerationPrompt[SID] => Int,
  initNumHITsToKeepActive: Int,
  _promptSource: Iterator[QAWSSDGenerationPrompt[SID]],
  settings: QASRLSettings,
  namingSuffix: String = "")(
  implicit annotationDataService: AnnotationDataService
) extends NumAssignmentsHITManager[QAWSSDGenerationPrompt[SID], List[VerbQA]](
  helper, numAssignmentsForPrompt, initNumHITsToKeepActive, _promptSource
) with StrictLogging {

  import helper._

  override def promptFinished(prompt: QAWSSDGenerationPrompt[SID]): Unit = {
    // sentenceTrackingActor ! GenerationFinished(prompt)
  }

  val wssdgenAssignmentAggregationStatusFilename = "wssdGenAssignmentAggregation" + namingSuffix

  // internal state of the "aggregator" - the table of genHITId -> list of approved assignments
  var genApprovedAssingments =
    annotationDataService.loadLiveData (genAssignmentAggregationStatusFilename)
      .map (_.mkString)
      .map (read[Map[String, List[Assignment[List[VerbQA]]]]] )
      .toOption.getOrElse {
      Map.empty[String, List[Assignment[List[VerbQA]]]]
    }

  val coverageStatsFilename = "coverageStats" + namingSuffix

  var coverageStats: Map[String, List[Int]] = annotationDataService.loadLiveData(coverageStatsFilename)
    .map(_.mkString)
    .map(read[Map[String, List[Int]]])
    .toOption.getOrElse(Map.empty[String, List[Int]])

  val feedbackFilename = "genFeedback" + namingSuffix

  var feedbacks =
    annotationDataService.loadLiveData(feedbackFilename)
      .map(_.mkString)
      .map(read[List[Assignment[List[VerbQA]]]])
      .toOption.foldK

  private[this] def save = {
    // save "aggregator"- table of approved (=finished) genAssignments to file
    Try (
      annotationDataService.saveLiveData (
        genAssignmentAggregationStatusFilename,
        write[Map[String, List[Assignment[List[VerbQA]]]]] (genApprovedAssingments) )
    ).toOptionLogging (logger).foreach (_ => logger.info ("generation assignments aggregation data saved.") )
    // save coverage info
    annotationDataService.saveLiveData(
      coverageStatsFilename,
      write(coverageStats))
    // save feedbacks info
    annotationDataService.saveLiveData(
      feedbackFilename,
      write(feedbacks))
    logger.info("Generation"+ namingSuffix+" data saved.")
  }

  // WSSD conversion completed!
  // handle wssd-generation HIT whose assignments were all completed
  def handleCompletedWSSDGenHIT(hit: HIT[QAWSSDGenerationPrompt[SID]],
                                allAssignments: List[Assignment[List[VerbQA]]]) : Unit = {
    // when only single Gen worker, don't inform AgreementActor
    if (allAssignments.size > 1) {
      // For each generator of the HIT, update QASRLGenerationAgreementManager
      // with all other genAssignments to compute agreement
      for (assignment <- allAssignments) {
        val otherWorkersAssignments = allAssignments.filter(_ != assignment)

        agreementAccuracyActor ! QAWSSDGenHITFinished(assignment,
          assignment.response,
          otherWorkersAssignments.map(_.response))
      }
    }
  }

  // add assignment to genApprovedAssingments map
  def addCompletedWSSDAssignmentToAggTable(hit: HIT[QAWSSDGenerationPrompt[SID]],
                                       assignment: Assignment[List[VerbQA]]) = {
    val genHITId = hit.hitId
    genApprovedAssingments.get (genHITId) match {
      // if genHITId not in map - add it with a List containing current assignment
      case None =>
        genApprovedAssingments = genApprovedAssingments.updated (genHITId, List (assignment) )
      // if it is in map - append assignment to list
      case Some (listOfAssignments) =>
        genApprovedAssingments = genApprovedAssingments.updated (genHITId, listOfAssignments :+ assignment )
    }
  }

  // currently only collects feedbacks to feedback file
  override def reviewAssignment(hit: HIT[QAWSSDGenerationPrompt[SID]], assignment: Assignment[List[VerbQA]]): Unit = {
    evaluateAssignment(hit, startReviewing(assignment), Approval(""))
    if(!assignment.feedback.isEmpty) {
      feedbacks = assignment :: feedbacks
      logger.info(s"Feedback: ${assignment.feedback}")
    }
    // add assignment to aggregator table
    addCompletedWSSDAssignmentToAggTable(hit, assignment)
    /*
       Aggregator logic - for computing gen-agreement:
       Check if genApprovedAssignments[genHITId] contain all required assignments for the HIT,
       and if so, call handleCompletedGenHIT, so it compute agreement
       and change worker stats & qualifications accordingly
        */
    val allAssignments = genApprovedAssingments.get(hit.hitId).get
    val nAssignmentForThisGenHIT = allAssignments.size
    val requiredNumOfAssignments = numAssignmentsForPrompt (hit.prompt)

    if (nAssignmentForThisGenHIT >= requiredNumOfAssignments) {
      handleCompletedWSSDGenHIT(hit, allAssignments)
    }

    // here is logic for reviewing assignments for coverage, if we shall wish to maintain worker coverage..
    /*
    val newQuestionRecord = assignment.response.size :: coverageStats.get(assignment.workerId).foldK
    coverageStats = coverageStats.updated(assignment.workerId, newQuestionRecord)
    val verbsCompleted = newQuestionRecord.size
    val questionsPerVerb = newQuestionRecord.sum.toDouble / verbsCompleted
    if(questionsPerVerb < settings.generationCoverageQuestionsPerVerbThreshold &&
         verbsCompleted > settings.generationCoverageGracePeriod) {
      config.service.associateQualificationWithWorker(
        new AssociateQualificationWithWorkerRequest()
          .withQualificationTypeId(coverageDisqualificationTypeId)
          .withWorkerId(assignment.workerId)
          .withIntegerValue(1)
          .withSendNotification(true))
    }
    */

    // Here can be logic for bonuses, if desired (for pipeline without validation)
    /*
    // Grant Bonus (automatically) if no validators in pipeline
    val validationPrompt = QASRLValidationPrompt(hit.prompt, hit.hitTypeId, hit.hitId, List(assignment.assignmentId), assignment.response)
    // validationPrompt is not really going to be sent to validators. it is only for the message to accuracyManager
    val numValidators = numValidationAssignmentForPrompt(validationPrompt)
    if (numValidators == 0) {
      // grant bonus by sending genAccruacyManager a message that tells him as if a validation
      // HIT approved all his questions (it only grant bonus)
      val allQuestionsInGenAssignment = assignment.response.map(_.question)
      agreementAccuracyManager ! QASRLValidationFinished(validationPrompt, allQuestionsInGenAssignment)
    }
    */
  }

  override lazy val receiveAux2: PartialFunction[Any, Unit] = {
    case SaveData => save
    case Pring => println("Generation"+ namingSuffix+" manager pringed.")

  }
}
