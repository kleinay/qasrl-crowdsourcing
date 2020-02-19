package qasrl.crowd

import java.nio.file.{Files, Paths}

import qasrl.crowd.util.PosTagger
import qasrl.crowd.util.implicits._
import qasrl.labeling.SlotBasedLabel
import cats.implicits._
import akka.actor._
import akka.stream.scaladsl.{Flow, Source}
import com.amazonaws.services.mturk.model._
import nlpdata.structure._
import nlpdata.util.HasTokens
import nlpdata.util.HasTokens.ops._
import nlpdata.util.Text
import nlpdata.util.PosTags
import nlpdata.util.LowerCaseStrings._
import nlpdata.datasets.wiktionary.Inflections
import spacro._
import spacro.tasks._
import spacro.util.Span
import upickle.default._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.StrictLogging

class QASRLEvaluationPipeline[SID : Reader : Writer : HasTokens](
  val allPrompts: Vector[QASRLArbitrationPrompt[SID]], // prompts to annotate
  val numArbitratorsForPrompt: Int,
  stage: Stage,
  frozenEvaluationHITTypeId: Option[String] = None,
  validationAgreementDisqualTypeLabel: Option[String] = None,
  alternativePromptReaderOpt: Option[Reader[QASRLArbitrationPrompt[SID]]] = None)(
  implicit val config: TaskConfig,
  val annotationDataService: AnnotationDataService,
  val settings: QASRLEvaluationSettings,
  val inflections: Inflections
) extends StrictLogging {

  import config.hitDataService

  val qual = new QualificationService()

  val approvalRateQualificationTypeID = "000000000000000000L0"
  val approvalRateRequirement = new QualificationRequirement()
    .withQualificationTypeId(approvalRateQualificationTypeID)
    .withComparator("GreaterThanOrEqualTo")
    .withIntegerValues(95)
    .withRequiredToPreview(false)

  val localeQualificationTypeID = "00000000000000000071"
  val localeRequirement = new QualificationRequirement()
    .withQualificationTypeId(localeQualificationTypeID)
    .withComparator("NotEqualTo")
    .withLocaleValues(new Locale().withCountry("IN"))
    .withRequiredToPreview(false)




  // todo hard code the desired groups of workers here
  val workersInGroup : Map[String, List[String]] = Map(
    "Group 1" -> List("A1FS8SBR4SDWYG", "A2A4UAFZ5LW71K"),
    "Group 2" -> List("A21LONLNBOB8Q", "A98E8M4QLI9RS"),
    "Group 3" -> List("AJQGWGESKQT4Y", "A3IR7DFEKLLLO", "A25AX0DNHKJCQT")
  )
  // for sandbox: biunlp username
//  val workersInGroup : Map[String, List[String]] = Map(
//    "all" -> List("A3UENPLNM9AQBK", "AZC7J87AH18DW")
//  )

  val groups : List[String] = workersInGroup.keys.toList

  val arbGroupRequirements : Map[String, QualificationRequirement] = groups.map { gr =>
    val productionArbitratorQualName = s"Arbitrator qualification for verbal-noun Q-A task [$gr]"
    val productionQualType = qual.findOrCreate(productionArbitratorQualName, s"""Access granted to the live annotation round in the arbitration task of verbal-noun Q-A [$gr]""".stripMargin)
    val inProductionReq = qual.createQualificationReq(productionQualType, shouldHave = true)
    gr -> inProductionReq
  }.toMap

  // grant qualification accordingly (if required)
  def grantGroupQualToWorkers(): Unit = for ((grp, workers) <- workersInGroup) {
    workers.foreach(worker => qual.verifyWorkerIsAssociatedWithQualification(arbGroupRequirements(grp).getQualificationTypeId, worker))
  }
  grantGroupQualToWorkers

  // group prompts to groups by mutual-exclusion of source workers (=generators).
  private val worker2group : Map[String, String] = for {
    (grp, workers) <- workersInGroup
    worker <- workers
  } yield worker -> grp

  private def nextGroup(grp: String): String = {
    groups((groups.indexOf(grp)+1) % groups.size)
  }

  private def assignGroupToArbPrompt(prompt: QASRLArbitrationPrompt[SID]): String = {
    val generators : List[String] = prompt.generators
    val generatorsGroups : Set[String] = generators.flatMap(w => worker2group.get(w)).toSet
    // if both generators from the same group - take subsequent group (arbitrarily)
    if (generatorsGroups.size == 1)
      nextGroup(generatorsGroups.head)
    else {   // generators come from two different groups. Take a different group
      (groups.toSet -- generatorsGroups).head
    }
  }

  /*
   temporal code - for duplicated_annotation IAA experiment -
      Split prompts' responses to two sets of 2:2 generators each.
      Then, replace upcoming occurrences of allPrompts with allPromptsSplitted.

  //val allPromptsSplitted = allPrompts.flatMap(IAA_Consolidated_Experiment.split_arb_prompt_by_worker_groups(_, workersInGroup))
  */

  // assign prompts to tasks by group
  val promptsByGroup: Map[String, Vector[QASRLArbitrationPrompt[SID]]] =
    allPrompts.groupBy(assignGroupToArbPrompt)
// For sandbox preview
//  val promptsByGroup: Map[String, Vector[QASRLArbitrationPrompt[SID]]] =
//    Map ("all" -> allPrompts)

  val activeGroups: List[String] = promptsByGroup.keys.toList

  lazy val (taskPageHeadLinks, taskPageBodyLinks) = {
    import scalatags.Text.all._
    val headLinks = List(
      link(
        rel := "stylesheet",
        href := "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-alpha.6/css/bootstrap.min.css",
        attr("integrity") := "sha384-rwoIResjU2yc3z8GV/NPeZWAv56rSmLldC3R/AZzGRnGxQQKnKkoFVhFQhNUwEyJ",
        attr("crossorigin") := "anonymous"))
    val bodyLinks = List(
      script(
        src := "https://code.jquery.com/jquery-3.1.1.slim.min.js",
        attr("integrity") := "sha384-A7FZj7v+d/sdmMqp/nOQwliLvUsJfDHW+k9Omg/a/EheAdgtzNs3hpfag6Ed950n",
        attr("crossorigin") := "anonymous"),
      script(
        src := "https://cdnjs.cloudflare.com/ajax/libs/jquery-cookie/1.4.1/jquery.cookie.min.js",
        attr("integrity") := "sha256-1A78rJEdiWTzco6qdn3igTBv9VupN3Q1ozZNTR4WE/Y=",
        attr("crossorigin") := "anonymous"),
      script(
        src := "https://cdnjs.cloudflare.com/ajax/libs/tether/1.4.0/js/tether.min.js",
        attr("integrity") := "sha384-DztdAPBWPRXSA/3eYEEUWrWCy7G5KFbe8fFjk5JAIxUYHKkDx6Qin1DkWx51bBrb",
        attr("crossorigin") := "anonymous"),
      script(
        src := "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-alpha.6/js/bootstrap.min.js",
        attr("integrity") := "sha384-vBWWzlZJ8ea9aCX4pEW3rVHjgjt7zpkNpZk+02D9phzyeVkE+jo0ieGizqPLForn",
        attr("crossorigin") := "anonymous"))
    (headLinks, bodyLinks)
  }

  lazy val sampleArbPrompt = allPrompts.head

  // arbitration task definition

  def createArbitrationTask(group: String):
  (ActorRef, QASRLEvaluationHITManager[SID], HITManager.Helper[QASRLArbitrationPrompt[SID], QANomResponse], ActorRef) =
  {
    val arbHITType = HITType(
      title = s"Consolidate decision and Question-Answer pairs about a verbal noun [$group]",
      description =
        s"""
      Given a sentence, a noun, and possibly several Q&A pairs collected from different annotators,
      You should: (1) Decide whether the target noun is a verbal noun.
      (2) Identify the most naturally phrased question in English
      for each subset of questions that ask about the same thing, and highlight all of its answers.
      You should then mark the other valid but redundant questions as redundant.
      Questions that are ungrammatical, not related to the verb, or that have no direct
      answer in the text should be marked as invalid.
      You should delete incorrect answers, modify existing ones if needed, and add new answers if missing.
      Maintain high agreement with our expert annotator team to stay qualified.
    """.trim,
      reward = settings.arbitrationReward,
      keywords = "language,english,question answering",
      qualRequirements = Array[QualificationRequirement](
        approvalRateRequirement, localeRequirement, arbGroupRequirements(group)
      ),
      autoApprovalDelay = 2592000L, // 30 days
      assignmentDuration = 600L)

    lazy val arbAjaxService = new Service[QASRLValidationAjaxRequest[SID]] {
      override def processRequest(request: QASRLValidationAjaxRequest[SID]) = request match {
        case QASRLValidationAjaxRequest(workerIdOpt, id) =>
          QASRLValidationAjaxResponse(None, id.tokens)
      }
    }

    lazy val arbTaskSpec = TaskSpecification.NoWebsockets[
      QASRLArbitrationPrompt[SID], QANomResponse, QASRLValidationAjaxRequest[SID]](
      settings.evaluationTaskKey, arbHITType, arbAjaxService, Vector(sampleArbPrompt),
      taskPageHeadElements = taskPageHeadLinks,
      taskPageBodyElements = taskPageBodyLinks,
      frozenHITTypeId = frozenEvaluationHITTypeId)

    import config.actorSystem

    var arbManagerPeek: QASRLEvaluationHITManager[SID] = null

    lazy val arbHelper = new HITManager.Helper(arbTaskSpec)
    lazy val arbManager: ActorRef = actorSystem.actorOf(
      Props {
        arbManagerPeek = new QASRLEvaluationHITManager(
          arbHelper,
          if (config.isProduction) (_ => numArbitratorsForPrompt) else (_ => 1),
          if (config.isProduction) 100 else 20,
          promptsByGroup(group).iterator)
        arbManagerPeek
      })

    lazy val arbActor = actorSystem.actorOf(Props(new TaskManager(arbHelper, arbManager)))

    // Return
    (arbManager, arbManagerPeek, arbHelper, arbActor)
  }

  // now create k tasks for k active groups (groups that got any prompt)
  val tasksObjects : List[(ActorRef, QASRLEvaluationHITManager[SID],
    HITManager.Helper[QASRLArbitrationPrompt[SID], QANomResponse], ActorRef)] =
    activeGroups.map(grp => createArbitrationTask(grp))

  // decompose to lists of manager, managerPeeks, helpers, actors.
  val arbManagers = tasksObjects.map(_._1)
  val arbManagerPeeks = tasksObjects.map(_._2)
  val arbHelpers = tasksObjects.map(_._3)
  val arbActors = tasksObjects.map(_._4)

  val arbHitTypeIds : List[String] = arbHelpers.map(_.taskSpec.hitTypeId)

  lazy val server = new Server(arbHelpers.map(_.taskSpec))

  // used to schedule data-saves
  private[this] var schedule: List[Cancellable] = Nil
  def startSaves(interval: FiniteDuration = 5 minutes): Unit = {
    if(schedule.exists(_.isCancelled) || schedule.isEmpty) {
      schedule = arbManagers.map(actor =>
        config.actorSystem.scheduler.schedule(
          2 seconds, interval, actor, SaveData)(
          config.actorSystem.dispatcher, actor)
      )
    }
  }
  def stopSaves = schedule.foreach(_.cancel())

  def setValHITsActive(n: Int) = for (arbManager <- arbManagers) {
    arbManager ! SetNumHITsActive(n)
  }

  import TaskManager.Message._
  def start(interval: FiniteDuration = 30 seconds) = {
    server
    arbHelpers.foreach(hlp =>
      logger.info(s"Arbitration HitTypeId: ${hlp.taskSpec.hitTypeId}")
    )
    startSaves()
    for (arbActor <- arbActors) {
      arbActor ! Start(interval, delay = 3 seconds)
    }
  }
  def stop() = {
    for (arbActor <- arbActors) {
      arbActor ! Stop
    }
    stopSaves
  }
  def delete() = for (arbActor <- arbActors) {
      arbActor ! Delete
  }
  def expire() = for (arbActor <- arbActors) {
    arbActor ! Expire
  }
  def update() = {
    server
    for (arbActor <- arbActors) {
      arbActor ! Update
    }
  }
  def save() = for (arbManager <- arbManagers) {
    arbManager ! SaveData
  }

  // for use while it's running. Ideally instead of having to futz around at the console calling these functions,
  // in the future you could have a nice dashboard UI that will help you examine common sources of issues

  def allInfos = alternativePromptReaderOpt match {
    case None =>
      arbHelpers.flatMap(hlp =>
        hitDataService.getAllHITInfo[QASRLArbitrationPrompt[SID], QANomResponse](hlp.taskSpec.hitTypeId).get)
    case Some(altReader) =>
      arbHelpers.flatMap(hlp =>
        hitDataService.getAllHITInfo[QASRLArbitrationPrompt[SID], QANomResponse](
          hlp.taskSpec.hitTypeId
        )(altReader, implicitly[Reader[QANomResponse]]).get
      )
  }

  val batchSize: Int = allPrompts.size
  val batchPrompts = allPrompts
  def batchInfos = allInfos.filter(gi => batchPrompts.contains(gi.hit.prompt))
  def batchHitIds = batchInfos.map(_.hit.hitId)
  def batchAssignmentIds = batchInfos.flatMap(_.assignments.map(_.assignmentId))
  def batchFeedbacks = arbManagerPeeks.flatMap(_.feedbacks).filter(a => batchHitIds.contains(a.hitId))

  def latestInfos(n: Int = 5) = allInfos
    .filter(_.assignments.nonEmpty)
    .sortBy(_.assignments.map(_.submitTime).max)
    .takeRight(n)

  // sorted increasing by submit time
  def infosForWorker(workerId: String) = {
    val scored = for {
      hi <- allInfos
      if hi.assignments.exists(_.workerId == workerId)
      workerAssignment = hi.assignments.find(_.workerId == workerId).get
      nonWorkerAssignments = hi.assignments.filter(_.workerId != workerId)
    } yield (HITInfo(hi.hit, workerAssignment :: nonWorkerAssignments), workerAssignment.submitTime)
    scored.sortBy(_._2).map(_._1)
  }


  def printFeedbacks(n: Int = 15) = arbManagerPeeks.flatMap(_.feedbacks).take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )

  def getAssignmentPrompt(a: spacro.Assignment[QANomResponse]): QASRLArbitrationPrompt[SID] = {
    hitDataService.getHIT[QASRLArbitrationPrompt[SID]](a.hitTypeId, a.hitId).get.prompt
  }

  def exportFeedbacks(feedbackFileName: String): Unit = {
    // only feedbacks of current batch
    val header : String = "qasrl_id\tsentence\tverb_idx\tverb_form\tfeedback\n"
    val feedbacks = batchFeedbacks.groupBy(a => getAssignmentPrompt(a)).flatMap {
      case (prompt, assignments) => assignments.map(assignment =>
        s"${prompt.id}\t${prompt.id.tokens.mkString(" ")}\t${prompt.targetIndex}\t${prompt.verbForm}\t${assignment.feedback}")
    }
    val path = Paths.get(feedbackFileName)
    Files.write(path, (header + feedbacks.mkString("\n")).getBytes())
  }

  def exportAllFeedbacks(feedbackFileName: String): Unit = {
    // all feedback of label
    val header : String = "qasrl_id\tverb_idx\tverb_form\tfeedback\n"
    val feedbacks = arbManagerPeeks.flatMap(_.feedbacks).groupBy(a => getAssignmentPrompt(a)).flatMap {
      case (prompt, assignments) => assignments.map(assignment =>
        s"${prompt.id}\t${prompt.targetIndex}\t${prompt.verbForm}\t${assignment.feedback}")
    }
    val path = Paths.get(feedbackFileName)
    Files.write(path, (header + feedbacks.mkString("\n")).getBytes())
  }

  def all_uncompleted_hitinfos = allInfos.filter(_.assignments.length < numArbitratorsForPrompt)
  def uncompleted_batch_hitinfos = batchInfos.filter(_.assignments.length < numArbitratorsForPrompt)

  def uncompleted_batch_prompts: List[QASRLArbitrationPrompt[SID]] = uncompleted_batch_hitinfos.map(_.hit.prompt)

  def finishedHITInfosByPrompt = arbHelpers.flatMap(_.finishedHITInfosByPromptIterator.toList)
  def completedPrompts : List[QASRLArbitrationPrompt[SID]] = finishedHITInfosByPrompt.map(_._1)

  def info(): Unit = {
    val currentBatchPromptsFinished = completedPrompts.filter(fp => batchPrompts.contains(fp))
    val uploadedCount = batchInfos.length

    val totalAssignments = batchSize * numArbitratorsForPrompt
    val completedAssCount = batchInfos.map(_.assignments.length).sum
    val groupsHitTypeIds : String = (activeGroups zip arbHelpers).map{
      case (grp : String, hlp: HITManager.Helper[QASRLArbitrationPrompt[SID], QANomResponse]) =>
        s"$grp: ${hlp.taskSpec.hitTypeId}"}
      .mkString("\n")
    println(s"Arbitration HitTypeIds: $groupsHitTypeIds")
    println()
    println(s"Active Phase: $stage")
    println()
    println(f"HITs (prompts): ${currentBatchPromptsFinished.size}/$batchSize (completed / total)")
    println(f"Assignments: $completedAssCount/$totalAssignments (completed / total)")
    println(f"Uploaded arbitration hits to MTurk: $uploadedCount")
  }
}
