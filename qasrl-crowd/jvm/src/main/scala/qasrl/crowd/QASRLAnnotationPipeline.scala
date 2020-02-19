package qasrl.crowd

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
import nlpdata.datasets.wiktionary.InflectedForms
import spacro._
import spacro.tasks._
import spacro.util.Span
import upickle.default._

import java.nio.file.{Files, Path, Paths}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.StrictLogging

sealed trait Stage
object Stage extends Enumeration {
  case object Expert extends Stage    // only for Sandbox
  case object WildCrowd extends Stage // for crowdsourcing out-of-the-box, no training.
                                      // Use coverage, accuracy (validation) and (IA-)agreement trackers for quality control.
  case object Trap extends Stage      // Trap is open to anyone
  case object Training extends Stage    // open only to specific workers (after Trap)
  case object Production extends Stage  // open only to specific workers after completing Training
}

class QASRLAnnotationPipeline[SID : Reader : Writer : HasTokens](
                                                                  val allNominalPrompts: Vector[QANomGenerationPrompt[SID]],
                                                                  //  val allIds: Vector[SID], // IDs of sentences to annotate
                                                                  numGenerationAssignmentsInProduction: Int,
                                                                  annotationDataService: AnnotationDataService,
                                                                  annotationStage: Stage = Stage.Expert,
                                                                  frozenGenerationHITTypeId: Option[String] = None,
                                                                  frozenValidationHITTypeId: Option[String] = None,
                                                                  generationAccuracyDisqualTypeLabel: Option[String] = None,
                                                                  generationCoverageDisqualTypeLabel: Option[String] = None,
                                                                  validationAgreementDisqualTypeLabel: Option[String] = None)(
  implicit val config: TaskConfig,
  val settings: QASRLSettings,
  val inflections: Inflections
) extends StrictLogging {

  // define numGenerationAssignmentsForPrompt for either production or sandbox
  def numGenerationAssignmentsForPrompt : QANomGenerationPrompt[SID] => Int =
    if(config.isProduction) (_ => numGenerationAssignmentsInProduction) else (_ => 2) // how many generators?
//    if(config.isProduction) (_ => 2) else (_ => 1) // how many generators?

  // define numValidatorsAssignmentsForPrompt for verbs, for either production or sandbox
  def numValidatorsAssignmentsForPrompt : QASRLValidationPrompt[SID] => Int =
    if(config.isProduction) (_ => 0) else (_ => 0)  // how many validators?

  implicit val ads = annotationDataService

  import config.hitDataService

  private def createQualification(name: String, description: String):QualificationType= {
    val qualResult = config.service.createQualificationType(
      new CreateQualificationTypeRequest()
        .withName(name)
        .withKeywords(KEYWORDS)
        .withDescription(description)
        .withQualificationTypeStatus(QualificationTypeStatus.Active)
    )
    qualResult.getQualificationType
  }

  // general list of all our qualifications on MTurk
  val ourRequesterName = "BIU NLP"
  lazy val allMTurkQualificationTypes = config.service.listQualificationTypes(
    new ListQualificationTypesRequest()
      .withQuery(ourRequesterName)
      .withMustBeOwnedByCaller(true)
      .withMustBeRequestable(false)
      .withMaxResults(100)
  ).getQualificationTypes.asScala.toList

  def findQualificationType (qualificationName: String): Option[QualificationType] = {
    val found = allMTurkQualificationTypes.find(_.getName == qualificationName)
    found
  }

  private def createDisqualificationReq(qualification: QualificationType,
                                        requiredToPreview: Boolean = false): QualificationRequirement = {
    val req = new QualificationRequirement()
      .withQualificationTypeId(qualification.getQualificationTypeId)
      .withComparator("DoesNotExist")
      .withRequiredToPreview(requiredToPreview)
    req
  }

  private def createQualificationReq(qualification: QualificationType,
                                     requiredToPreview: Boolean = false): QualificationRequirement = {
    val req = new QualificationRequirement()
      .withQualificationTypeId(qualification.getQualificationTypeId)
      .withComparator("Exists")
      .withRequiredToPreview(requiredToPreview)
    req
  }

  private val KEYWORDS = "language,english,question answering"

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

   /*
  Stage related qualifications:  We will have:
      1) TrapDisQual qualification granted to all those who were manually tested per performance
            we will use this to disqualify those from future Traps
      2) TrainingQual qualification granted only for those in Training stage.
            when promoting a worker to production, we remove grant him ProductionQual, which disqualifies him from Training HITs.
            when "downgrading" a worker from training to be disqualified, we remove it's TrainingQual.
      3) ProductionQual qualification granted to those completing the Training stage successfully.
  */

  val nomTrapQualTypeName = "Trap phase qualification for QA-writing on verbal nouns task"
  val nomTrapQualType = findQualificationType(nomTrapQualTypeName).getOrElse {
    logger.info("Generating Trap phase qualification type...")
    createQualification(nomTrapQualTypeName, "Workers from previous Training rounds cannot participate in these 'audition' HITs.")
  }
  val nomTrapQualTypeId = nomTrapQualType.getQualificationTypeId
  val nomTrapPhaseRequirement = createDisqualificationReq(nomTrapQualType, requiredToPreview = true)

  val nomTrainingQualTypeName = "Training and Qualification phase qualification for QA-writing on verbal nouns task"
  val nomTrainingQualType = findQualificationType(nomTrainingQualTypeName).getOrElse {
    logger.info("Generating Training phase qualification type...")
    createQualification(nomTrainingQualTypeName, "Only workers in the Training and Qualification phase can do participate in these training HITs.")
  }
  val nomTrainingQualTypeId = nomTrainingQualType.getQualificationTypeId
  val nomTrainingPhaseRequirement = createQualificationReq(nomTrainingQualType, requiredToPreview = true)

  val nomProductionQualTypeName = "Production phase qualification for QA-writing on verbal nouns task"
  val nomProductionQualType = findQualificationType(nomProductionQualTypeName).getOrElse {
    logger.info("Generating Production phase qualification type...")
    createQualification(nomProductionQualTypeName, "Only workers in the Production phase can participate in these HITs.")
  }
  val nomProductionQualTypeId = nomProductionQualType.getQualificationTypeId
  val nomProductionPhaseRequirement = createQualificationReq(nomProductionQualType, requiredToPreview = true)

  // disqualify Production workers from Training stage HITs
  val nomProductionPhaseWorkersDisqualificationFromTrainingRequirement =
    createDisqualificationReq(nomProductionQualType, requiredToPreview = true)

  // disqualify (previous) qualified workers from Trap stage HITs
  val nomQualifiedWorkersDisqualificationFromTrapRequirement =
    createDisqualificationReq(nomTrainingQualType, requiredToPreview = true)

  // now combine the current-stage related requirements for the generation HITs
  val stageRelatedRequirements : Array[QualificationRequirement] = annotationStage match {
    case Stage.Trap => Array(nomTrapPhaseRequirement, nomQualifiedWorkersDisqualificationFromTrapRequirement)
    case Stage.Training => Array(nomTrainingPhaseRequirement, nomProductionPhaseWorkersDisqualificationFromTrainingRequirement)
    case Stage.Production => Array(nomProductionPhaseRequirement)
    case Stage.Expert => Array.empty
    case Stage.WildCrowd => Array.empty
  }

  val stageRelatedTaskTitleSuffix : String = annotationStage match {
    case Stage.Trap => ""
    case Stage.Training => "[Training]"
    case Stage.Production => "[Production]"
    case Stage.Expert => "[Internal]"
    case Stage.WildCrowd => ""
  }

  // Generators Agreement Disqualification
  val genAgreementDisqualTypeName = s"Question-answer writing inter-worker agreement disqualification"
  val genAgreementDisqualType = findQualificationType(genAgreementDisqualTypeName).getOrElse {
    logger.info("Generating generation agreement disqualification type...")
    createQualification(genAgreementDisqualTypeName, "Inter-worker agreement on the question-answer writing task is too low.")
  }
  val genAgreementDisqualTypeId = genAgreementDisqualType.getQualificationTypeId
  val genAgreementRequirement = createDisqualificationReq(genAgreementDisqualType)

  // Generators Accuracy (vs. validators) Disqualification
  val genAccDisqualTypeLabelString = generationAccuracyDisqualTypeLabel.fold("")(x => s"[$x] ")
  val genAccDisqualTypeName = s"${genAccDisqualTypeLabelString}Question-answer writing accuracy disqualification"
  val genAccDisqualType = findQualificationType(genAccDisqualTypeName).getOrElse {
    logger.info("Generating generation accuracy disqualification type...")
    createQualification(genAccDisqualTypeName, "Accuracy on the question-answer writing task is too low.")
  }
  val genAccDisqualTypeId = genAccDisqualType.getQualificationTypeId
  val genAccuracyRequirement = createDisqualificationReq(genAccDisqualType)

  // Generators Coverage (QAs per target) Disqualification
  val genCoverageDisqualTypeLabelString = generationCoverageDisqualTypeLabel.fold("")(x => s"[$x] ")
  val genCoverageDisqualTypeName = s"${genCoverageDisqualTypeLabelString} Questions asked per target disqualification"
  val genCoverageDisqualType = findQualificationType(genCoverageDisqualTypeName).getOrElse{
    logger.info("Generating generation coverage disqualification type...")
    createQualification(genCoverageDisqualTypeName, "Number of questions asked for each target in our question-answer " +
      "pair generation task is too low.")
  }
  val genCoverageDisqualTypeId = genCoverageDisqualType.getQualificationTypeId
  val genCoverageRequirement = createDisqualificationReq(genCoverageDisqualType)


  val valAgrDisqualTypeLabelString = validationAgreementDisqualTypeLabel.fold("")(x => s"[$x] ")
  val valAgrDisqualTypeName = s"${valAgrDisqualTypeLabelString}Question answering agreement disqualification"
  val valAgrDisqualType = findQualificationType(valAgrDisqualTypeName).getOrElse{
    logger.info("Generating validation disqualification type...")
    createQualification(valAgrDisqualTypeName, "Agreement with other annotators on answers and validity " +
      "judgments in our question answering task is too low.")
  }
  val valAgrDisqualTypeId = valAgrDisqualType.getQualificationTypeId
  val valAgreementRequirement = createDisqualificationReq(valAgrDisqualType)


  // NOTE may need to call multiple times to cover all workers... sigh TODO pagination
  def resetAllQualificationValues = {
    def revokeAllWorkerQuals(qualTypeId: String) = {
      val quals = config.service.listWorkersWithQualificationType(
        new ListWorkersWithQualificationTypeRequest()
          .withQualificationTypeId(qualTypeId)
          .withMaxResults(100)
      ).getQualifications.asScala.toList
      quals.foreach(qual =>
        config.service.disassociateQualificationFromWorker(
          new DisassociateQualificationFromWorkerRequest()
            .withQualificationTypeId(qualTypeId)
            .withWorkerId(qual.getWorkerId)
        )
      )
    }
    val activeQualsList = List(
      genAccDisqualTypeId,
      genAgreementDisqualTypeId,
      genCoverageDisqualTypeId,
      valAgrDisqualTypeId)

    activeQualsList.foreach(revokeAllWorkerQuals)
  }

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

  val genHITType = HITType(
    title = s"Write question-answer pairs about verbal nouns  $stageRelatedTaskTitleSuffix",
    description = s"""
      Given a sentence and a noun from that sentence,
      write questions and answers about that noun.
      Questions must adhere to a certain template,
      provided by autocomplete functionality.
      Maintain high accuracy to stay qualified.
    """.trim.replace("\\s+", " "),
    reward = settings.generationReward,
    keywords = "language,english,question answering",
    qualRequirements = Array[QualificationRequirement](
      approvalRateRequirement, localeRequirement, // genAccuracyRequirement,
      genAgreementRequirement, genCoverageRequirement
    ) ++ stageRelatedRequirements,
    autoApprovalDelay = 2592000L, // 30 days
    assignmentDuration = 3600L)

  lazy val genAjaxService = new Service[QANomGenerationAjaxRequest[SID]] {
    override def processRequest(request: QANomGenerationAjaxRequest[SID]) = request match {
      case QANomGenerationAjaxRequest(workerIdOpt, QANomGenerationPrompt(id, verbIndex, verbForm)) =>
        val questionListsOpt = for {
          genManagerP <- Option(genManagerPeek)
          workerId <- workerIdOpt
          qCounts <- genManagerP.coverageStats.get(workerId)
        } yield qCounts
        val questionLists = questionListsOpt.getOrElse(Nil)

        // this is for taking worker stat from accuracyTrackerPeek which contains accuracy by validation
        //        val workerStatsOpt = for {
        //          accTrackP <- Option(accuracyTrackerPeek)
        //          workerId <- workerIdOpt
        //          stats <- accTrackP.allWorkerStats.get(workerId)
        //        } yield stats

        // this is for taking worker stat from genAgreementTrackerPeek which contains accuracy by agreement
        val workerStatsOpt = for {
          accTrackP <- Option(genAgreementTrackerPeek)
          workerId <- workerIdOpt
          stats <- accTrackP.allWorkerStats.get(workerId)
        } yield stats

        val stats = GenerationStatSummary(
          numVerbsCompleted = questionLists.size,
          numQuestionsWritten = questionLists.sum,
          workerStatsOpt = workerStatsOpt)

        val tokens = id.tokens
        // a Vector corresponding to verbForms : Vector[String]
        val inflectedForms : Option[InflectedForms] =
          inflections.getInflectedForms(verbForm.lowerCase)
        QANomGenerationAjaxResponse(stats, tokens, inflectedForms)
    }
  }

  // validation task definition

  val valHITType = HITType(
    title = s"Answer simple questions about a sentence",
    description = s"""
      Given a sentence and several questions about it,
      highlight the part of the sentence that answers each question,
      and mark questions that are invalid or redundant.
      Maintain high agreement with others to stay qualified.
    """.trim,
    reward = settings.validationReward,
    keywords = "language,english,question answering",
    qualRequirements = Array[QualificationRequirement](
      approvalRateRequirement, localeRequirement, valAgreementRequirement
    ),
    autoApprovalDelay = 2592000L, // 30 days
    assignmentDuration = 3600L)

  lazy val valAjaxService = new Service[QASRLValidationAjaxRequest[SID]] {
    override def processRequest(request: QASRLValidationAjaxRequest[SID]) = request match {
      case QASRLValidationAjaxRequest(workerIdOpt, id) =>
        val workerInfoSummaryOpt = for {
          valManagerP <- Option(valManagerPeek)
          workerId <- workerIdOpt
          info <- valManagerP.allWorkerInfo.get(workerId)
        } yield info.summary

        QASRLValidationAjaxResponse(workerInfoSummaryOpt, id.tokens)
    }
  }

  lazy val sampleValPrompt = QASRLValidationPrompt[SID](
    allNominalPrompts.head, "", "", List(""),
    List(QANomResponse(3,true,"expect",
      List( VerbQA(3, "Who expects something?", List(Span(0, 0), Span(2, 2))),
            VerbQA(3, "What does someone expects?", List(Span(4, 15)))))))

  lazy val valTaskSpec = TaskSpecification.NoWebsockets[QASRLValidationPrompt[SID], List[QASRLValidationAnswer], QASRLValidationAjaxRequest[SID]](
    settings.validationTaskKey, valHITType, valAjaxService, Vector(sampleValPrompt),
    taskPageHeadElements = taskPageHeadLinks,
    taskPageBodyElements = taskPageBodyLinks,
    frozenHITTypeId = frozenValidationHITTypeId)

  // hit management --- circularly defined so they can communicate

  import config.actorSystem

  var accuracyTrackerPeek: QASRLGenerationAccuracyManager[SID] = null

  lazy val accuracyTracker: ActorRef = actorSystem.actorOf(
    Props {
      accuracyTrackerPeek = new QASRLGenerationAccuracyManager[SID](genAccDisqualTypeId)
      accuracyTrackerPeek
    }
  )

  // Actor for tracking generators agreement
  var genAgreementTrackerPeek: QASRLGenerationAgreementManager[SID] = null

  lazy val genAgreementTracker: ActorRef = actorSystem.actorOf(
    Props {
      genAgreementTrackerPeek = new QASRLGenerationAgreementManager[SID](genAgreementDisqualTypeId)
      genAgreementTrackerPeek
    }
  )


  var valManagerPeek: QASRLValidationHITManager[SID] = null

  lazy val valHelper = new HITManager.Helper(valTaskSpec)
  lazy val valManager: ActorRef = actorSystem.actorOf(
    Props {
      valManagerPeek = new QASRLValidationHITManager(
        valHelper,
        valAgrDisqualTypeId,
        accuracyTracker,
        // sentenceTracker,
        numValidatorsAssignmentsForPrompt, // how many validators per HIT?
        if(config.isProduction) 100 else 100)
      valManagerPeek
    })

  lazy val valActor = actorSystem.actorOf(Props(new TaskManager(valHelper, valManager)))

  // Actor for aggregating generation responses to a single validation prompt
  var genAggregatorPeek: GenerationAggregationManager[SID] = null

  lazy val genAggregator: ActorRef = actorSystem.actorOf(
    Props {
      genAggregatorPeek = new GenerationAggregationManager[SID](
        genAgreementTracker,
        valHelper,
        valManager,
        numGenerationAssignmentsForPrompt
      )
      genAggregatorPeek
    })

  val genTaskSpec = TaskSpecification.NoWebsockets[QANomGenerationPrompt[SID], QANomResponse, QANomGenerationAjaxRequest[SID]](
    settings.generationTaskKey, genHITType, genAjaxService, allNominalPrompts,
    taskPageHeadElements = taskPageHeadLinks,
    taskPageBodyElements = taskPageBodyLinks,
    frozenHITTypeId = frozenGenerationHITTypeId)

  var genManagerPeek: QASRLGenerationHITManager[SID] = null

  val genHelper = new HITManager.Helper[QANomGenerationPrompt[SID], QANomResponse](genTaskSpec)
  val genManager: ActorRef = actorSystem.actorOf(
    Props {
      genManagerPeek = new QASRLGenerationHITManager[SID](
        genHelper,
        valHelper,
        valManager,
        genAgreementTracker,
        accuracyTracker,
        genCoverageDisqualTypeId,
        // sentenceTracker,
        numGenerationAssignmentsForPrompt,
        numValidatorsAssignmentsForPrompt,
        if(config.isProduction) 100 else 100,
        allNominalPrompts.iterator)  // the prompts itarator determines what genHITs are generated
      genManagerPeek
    }
  )
  val genActor = actorSystem.actorOf(Props(new TaskManager(genHelper, genManager)))


  lazy val server = new Server(List(genTaskSpec))

  // used to schedule data-saves
  private[this] var schedule: List[Cancellable] = Nil
  def startSaves(interval: FiniteDuration = 5 minutes): Unit = {
    if(schedule.exists(_.isCancelled) || schedule.isEmpty) {
      schedule = List(genManager, // valManager,
        accuracyTracker, genAgreementTracker, genAggregator).map(actor =>
        config.actorSystem.scheduler.schedule(
          2 seconds, interval, actor, SaveData)(
          config.actorSystem.dispatcher, actor)
      )
    }
  }
  def stopSaves = schedule.foreach(_.cancel())

  def setGenHITsActiveEach(n: Int) = {
    genManager ! SetNumHITsActive(n)
  }
  def setValHITsActive(n: Int) = {
    valManager ! SetNumHITsActive(n)
  }

  import TaskManager.Message._
  def start(interval: FiniteDuration = 30 seconds) = {
    server
    startSaves()
    genActor ! Start(interval, delay = 2 seconds)
    valActor ! Start(interval, delay = 3 seconds)
  }
  def stop() = {
    genActor ! Stop
    valActor ! Stop
    stopSaves
  }
  def delete() = {
    genActor ! Delete
    valActor ! Delete
  }
  def expire() = {
    genActor ! Expire
    valActor ! Expire
  }
  def update() = {
    server
    genActor ! Update
    valActor ! Update
  }
  def save() = {
    // sentenceTracker ! SaveData
    accuracyTracker ! SaveData
    genAgreementTracker ! SaveData
    genManager ! SaveData
    valManager ! SaveData
    genAggregator ! SaveData
  }

  // for use while it's running. Ideally instead of having to futz around at the console calling these functions,
  // in the future you could have a nice dashboard UI that will help you examine common sources of issues

  def allGenInfos = hitDataService.getAllHITInfo[QANomGenerationPrompt[SID], QANomResponse](genTaskSpec.hitTypeId).get

  def allValInfos = hitDataService.getAllHITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]](valTaskSpec.hitTypeId).get

  def currentGenSentences: List[(SID, String)] = {
    genHelper.activeHITInfosByPromptIterator.map(_._1.id).map(id =>
      id -> Text.render(id.tokens)
    ).toList
  }

  def latestValInfos(n: Int = 5) = allValInfos
    .filter(_.assignments.nonEmpty)
    .sortBy(_.assignments.map(_.submitTime).max)
    .takeRight(n)

  // sorted increasing by submit time
  def infosForGenWorker(workerId: String) = {
    val scored = for {
      hi <- allValInfos
      sourceAssignment <- hitDataService.getAssignmentsForHIT[QANomResponse](genTaskSpec.hitTypeId, hi.hit.prompt.sourceHITId).toOptionLogging(logger).toList.flatten
      if sourceAssignment.assignmentId == hi.hit.prompt.sourceAssignmentId
      if sourceAssignment.workerId == workerId
    } yield (hi, sourceAssignment.submitTime)
    scored.sortBy(_._2).map(_._1)
  }

  // sorted increasing by submit time
  def infosForValWorker(workerId: String) = {
    val scored = for {
      hi <- allValInfos
      if hi.assignments.exists(_.workerId == workerId)
      workerAssignment = hi.assignments.find(_.workerId == workerId).get
      nonWorkerAssignments = hi.assignments.filter(_.workerId != workerId)
    } yield (HITInfo(hi.hit, workerAssignment :: nonWorkerAssignments), workerAssignment.submitTime)
    scored.sortBy(_._2).map(_._1)
  }

  def renderValidation(info: HITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]]) = {
    val sentence = info.hit.prompt.genPrompt.id.tokens
    val genWorkerString = hitDataService
      .getAssignmentsForHIT[QANomResponse](genTaskSpec.hitTypeId, info.hit.prompt.sourceHITId).get
      .find(_.assignmentId == info.hit.prompt.sourceAssignmentId)
      .fold("")(_.workerId)
    Text.render(sentence) + "\n" +
      info.hit.prompt.qaPairs.zip(info.assignments.map(_.response).transpose).map {
        case (VerbQA(verbIndex, question, answers), validationAnswers) =>
          val answerString = answers.map(s => Text.renderSpan(sentence, (s.begin to s.end).toSet)).mkString(" / ")
          val validationRenderings = validationAnswers.map(QASRLValidationAnswer.render(sentence, _))
          val allValidationsString = validationRenderings.toList match {
            case Nil => ""
            case head :: tail => f"$head%20s(${tail.mkString("; ")}%s)"
          }
          f"$genWorkerString%-20s $question%-35s --> $answerString%20s | $allValidationsString"
      }.mkString("\n") + "\n"
  }

  // print LATEST and WORST n entries for val or gen worker, n default Int.MaxValue

  def printLatestGenInfos(workerId: String, n: Int = 5) =
    infosForGenWorker(workerId)
      .takeRight(n)
      .map(renderValidation)
      .foreach(println)

  def printWorstGenInfos(workerId: String, n: Int = 5) =
    infosForGenWorker(workerId)
      .sortBy(_.assignments.flatMap(_.response).filter(_.isInvalid).size)
      .takeRight(n)
      .map(renderValidation)
      .foreach(println)

  def printLatestValInfos(workerId: String, n: Int = 5) =
    infosForValWorker(workerId)
      .takeRight(n)
      .map(renderValidation)
      .foreach(println)

  def printWorstValInfos(workerId: String, n: Int = 5) =
    infosForValWorker(workerId)
      .sortBy { hi =>
      if(hi.assignments.size <= 1) Int.MinValue else {
        val totalQAPairs = hi.hit.prompt.qaPairs.size.toDouble
        val agreedQAPairs = hi.assignments.head.response
          .zip(hi.assignments.tail.map(a => a.response.map(a.workerId -> _)).transpose)
          .map { case (givenAnswer, refPairs) =>
            QASRLValidationResponseComparison(
              givenAnswer,
              refPairs.filter(p => !valManagerPeek.blockedValidators.contains(p._1))
            ) }
          .filter(_.isAgreement).size
        totalQAPairs - agreedQAPairs } }
      .takeRight(n)
      .map(renderValidation)
      .foreach(println)

  case class StatSummary(
    workerId: String,
    numVerbs: Option[Int],
    numQs: Option[Int],
    accuracy: Option[Double],
    genAgreement: Option[Double],
    numAs: Option[Int],
    numInvalidAnswers: Option[Int],
    pctBad: Option[Double],
    agreement: Option[Double],
    earnings: Double) {
    override def toString = f"worker $workerId:  #verb=$numVerbs   #Qs=$numQs  agreement-rate=$genAgreement  earned=$earnings"
  }

  case class AggregateStatSummary(
    numVerbs: Int,
    numQs: Int,
    numAs: Int,
    numInvalidAnswers: Int,
    totalCost: Double) {
    def combine(worker: StatSummary) = AggregateStatSummary(
      numVerbs + worker.numVerbs.getOrElse(0),
      numQs + worker.numQs.getOrElse(0),
      numAs + worker.numAs.getOrElse(0) + worker.numInvalidAnswers.getOrElse(0),
      numInvalidAnswers + worker.numInvalidAnswers.getOrElse(0),
      totalCost + worker.earnings
    )
  }
  object AggregateStatSummary {
    def empty = AggregateStatSummary(0, 0, 0, 0, 0.0)
  }

  object StatSummary {
    def makeFromStatsAndInfo(
      accStats: Option[QASRLGenerationWorkerStats],
      stats: Option[QASRLGenerationWorkerStats],
      info: Option[QASRLValidationWorkerInfo]
    ) = stats.map(_.workerId).map { wid =>
      StatSummary(
        workerId = wid,
        numVerbs = stats.map(_.numAssignmentsCompleted),
        numQs = stats.map(_.numQAPairsWritten),
        accuracy = stats.map(_.accuracy),
        genAgreement = stats.map(_.genAgreementAccuracy),
        numAs = info.map(i => i.numAnswerSpans + i.numInvalids),
        numInvalidAnswers = info.map(_.numInvalids),
        pctBad = info.map(_.proportionInvalid * 100.0),
        agreement = info.map(_.agreement),
        earnings = stats.fold(0.0)(_.earnings) + info.fold(0.0)(_.earnings)
      )
    }
  }

  def allStatSummaries = {
    val allStats = accuracyTrackerPeek.allWorkerStats
    val genAgrStats = genAgreementTrackerPeek.allWorkerStats
    val allInfos = valManagerPeek.allWorkerInfo
    (allStats.keys ++ genAgrStats.keys ++ allInfos.keys).toSet.toList.flatMap((wid: String) =>
      StatSummary.makeFromStatsAndInfo(allStats.get(wid), genAgrStats.get(wid), allInfos.get(wid))
    )
  }

  def printStatsHeading =
    println(f"${"Worker ID"}%14s  ${"Verbs"}%5s  ${"Qs"}%5s  ${"Acc"}%4s  ${"As"}%5s  ${"%Bad"}%5s  ${"Agr"}%4s  $$")

  def printSingleStatSummary(ss: StatSummary): Unit = ss match {
    case StatSummary(wid, numVerbsOpt, numQsOpt, accOpt, genAgreeOpt, numAsOpt, numInvalidsOpt, pctBadOpt, agrOpt, earnings)=>
      val numVerbs = numVerbsOpt.getOrElse("")
      val numQs = numQsOpt.getOrElse("")
      val acc = accOpt.foldMap(pct => f"$pct%.2f")
      val genAgr = genAgreeOpt.foldMap(pct => f"$pct%.2f")
      val numAs = numAsOpt.getOrElse("")
      val pctBad = pctBadOpt.foldMap(pct => f"$pct%4.2f")
      val agr = agrOpt.foldMap(pct => f"$pct%.2f")
      println(f"$wid%14s  $numVerbs%5s  $numQs%5s  $acc%4s  $genAgr%4s  $numAs%5s  $pctBad%5s  $agr%4s  $earnings%.2f")
  }

  def statsForWorker(workerId: String): Option[StatSummary] = allStatSummaries.find(_.workerId == workerId)

  def printStatsForWorker(workerId: String) = statsForWorker(workerId) match {
    case None => println("No stats for worker.")
    case Some(ss) =>
      printStatsHeading
      printSingleStatSummary(ss)
  }

  /*
  Info about current batch (HITs uploaded in the current run of setup.

  Note that many data that is dependant of annotationDataService of hitDataService are saving
  all their info in the same files as long as we are in the same label, so, e.g. allGenInfos
  contains genInfos of HITs of previous batches of the same label.
   */
  def batchSize: Int = allNominalPrompts.size
  val batchPrompts = allNominalPrompts
  def batchGenInfos = allGenInfos.filter(gi => allNominalPrompts.contains(gi.hit.prompt))
  def batchHitIds = batchGenInfos.map(_.hit.hitId)
  def batchAssignmentIds = batchGenInfos.flatMap(_.assignments.map(_.assignmentId))
  def batchFeedbacks = genManagerPeek.feedbacks.filter(a => batchHitIds.contains(a.hitId))


  def getAssignmentPrompt(a: spacro.Assignment[QANomResponse]): QANomGenerationPrompt[SID] = {
    hitDataService.getHIT[QANomGenerationPrompt[SID]](a.hitTypeId, a.hitId).get.prompt
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
    val feedbacks = genManagerPeek.feedbacks.groupBy(a => getAssignmentPrompt(a)).flatMap {
      case (prompt, assignments) => assignments.map(assignment =>
        s"${prompt.id}\t${prompt.targetIndex}\t${prompt.verbForm}\t${assignment.feedback}")
    }
    val path = Paths.get(feedbackFileName)
    Files.write(path, (header + feedbacks.mkString("\n")).getBytes())
  }


  def printStatsSorted[B : Ordering](sortFn: StatSummary => B) = {
    val summaries = allStatSummaries.sortBy(sortFn)
    printStatsHeading
    summaries.foreach(printSingleStatSummary)
  }

  def printQStats = printStatsSorted(-_.numQs.getOrElse(0))
  def printAStats = printStatsSorted(-_.numAs.getOrElse(0))
  def printStatsByAgreement = printStatsSorted(_.genAgreement.getOrElse(0.0).toDouble *(-1.0))
  def printStatsByVerbs = printStatsSorted(-_.numVerbs.getOrElse(0))
  def printStats = printStatsByVerbs

  def printAllStats = genManagerPeek.coverageStats.toList
    .sortBy(-_._2.size)
    .map { case (workerId, numQs) => {
      val statSummary: StatSummary = statsForWorker(workerId).get
      val isVerbalTrueCount = numQs.size
      val numPredicates = statSummary.numVerbs.get
      val proportionIsVerbal = isVerbalTrueCount.toDouble / numPredicates
      val agreementRate = statSummary.genAgreement.get
      f"$workerId%s\tHITs:$numPredicates%d \tperc. verbal: $proportionIsVerbal%.2f \tQAs-per-verbal-noun:${numQs.sum.toDouble / numQs.size}%.2f \tIAA:$agreementRate%.2f"
    } }
    .foreach(println)

  def printGenFeedback(n: Int) = batchFeedbacks.take(n).foreach(a => {
    val prompt = getAssignmentPrompt(a)
    val sentence : String = prompt.id.tokens.mkString(" ")
    val target : String = prompt.id.tokens(prompt.targetIndex)
    println(a.workerId + " --- S:\t" + sentence)
    println(s"  Target: $target (${prompt.targetIndex}) \tVerb-Form: ${a.response.verbForm}\t" +
      s"is-verbal: " + a.response.isVerbal.toString )
    println("  Feedback: " + a.feedback)
  })
  def printValFeedback(n: Int) = valManagerPeek.feedbacks.take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )

  def printAllFeedbacks(n: Int = Int.MaxValue) = {
    println("Generation (verbs):")
    printGenFeedback(n)
//    println("\nValidation:")
//    printValFeedback(n)
  }

  def aggregateStats = allStatSummaries.foldLeft(AggregateStatSummary.empty)(_ combine _)

  def printAggregateStats = aggregateStats match {
    case AggregateStatSummary(numVerbs, numQs, numAs, numInvalidAnswers, totalCost) =>
      println(f"${"Num verbs:"}%-20s$numVerbs%d")
      println(f"${"Num questions:"}%-20s$numQs%d")
      println(f"${"Num answers:"}%-20s$numAs%d")
      println(f"${"Num invalids:"}%-20s$numInvalidAnswers%d")
      println(f"${"Total cost:"}%-20s$totalCost%.2f")
  }

  def getGenHitTypeId: String = genHelper.taskSpec.hitTypeId

  // Tracking progress
  def seeProgress: Unit = {
    val finishedPrompts = genHelper.finishedHITInfosByPromptIterator.map(_._1).toList
    val currentBatchFinished = finishedPrompts.filter(fp => batchPrompts.contains(fp))
    println(f"Finished HITs: This batch- ${currentBatchFinished.size} ;    General- ${finishedPrompts.size}")
    println(f"Currently active HITs: ${genHelper.numActiveHITs}")
    println("Workers General workload:")
    val approved : List[spacro.Assignment[QANomResponse]] = genManagerPeek.genApprovedAssingments.values.toList.flatten
    val doneByWorker = approved.groupBy(_.workerId)
    for (worker <- doneByWorker.keys) { println(f"${worker}: ${doneByWorker(worker).size} assignments done") }
    println("Workers Batch workload:")
    val b_approved = approved.filter(a => batchAssignmentIds.contains(a.assignmentId))
    val b_doneByWorker = b_approved.groupBy(_.workerId)
    for (worker <- b_doneByWorker.keys) { println(f"${worker}: ${b_doneByWorker(worker).size} assignments done") }
  }
}
