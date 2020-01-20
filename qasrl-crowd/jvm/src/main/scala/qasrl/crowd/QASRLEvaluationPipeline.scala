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
import spacro._
import spacro.tasks._
import spacro.util.Span
import upickle.default._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.StrictLogging

class QASRLEvaluationPipeline[SID : Reader : Writer : HasTokens](
  val allPrompts: Vector[QASRLArbitrationPrompt[SID]], // IDs of sentences to annotate
  val numValidationsForPrompt: Int,
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


  val productionArbitratorQualName = "Arbitrator qualification for verbal-noun Q-A task"
  val productionQualType = qual.findOrCreate(productionArbitratorQualName, """Access granted to the live annotation round in the arbitration task of verbal-noun Q-A""".stripMargin)
  val inProductionReq = qual.createQualificationReq(productionQualType, shouldHave = true)


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

  // arbitration task definition

  val arbHITType = HITType(
    title = s"Consolidate decision and Question-Answer pairs about a verbal noun",
    description = s"""
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
      approvalRateRequirement, localeRequirement, inProductionReq
    ),
    autoApprovalDelay = 2592000L, // 30 days
    assignmentDuration = 600L)

  lazy val arbAjaxService = new Service[QASRLValidationAjaxRequest[SID]] {
    override def processRequest(request: QASRLValidationAjaxRequest[SID]) = request match {
      case QASRLValidationAjaxRequest(workerIdOpt, id) =>
        QASRLValidationAjaxResponse(None, id.tokens)
    }
  }

  lazy val sampleArbPrompt = allPrompts.head

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
        if(config.isProduction) (_ => numValidationsForPrompt) else (_ => 1),
        if(config.isProduction) 100 else 20,
        allPrompts.iterator)
      arbManagerPeek
    })

  lazy val arbActor = actorSystem.actorOf(Props(new TaskManager(arbHelper, arbManager)))

  lazy val server = new Server(List(arbTaskSpec))

  // used to schedule data-saves
  private[this] var schedule: List[Cancellable] = Nil
  def startSaves(interval: FiniteDuration = 5 minutes): Unit = {
    if(schedule.exists(_.isCancelled) || schedule.isEmpty) {
      schedule = List(arbManager).map(actor =>
        config.actorSystem.scheduler.schedule(
          2 seconds, interval, actor, SaveData)(
          config.actorSystem.dispatcher, actor)
      )
    }
  }
  def stopSaves = schedule.foreach(_.cancel())

  def setValHITsActive(n: Int) = {
    arbManager ! SetNumHITsActive(n)
  }

  import TaskManager.Message._
  def start(interval: FiniteDuration = 30 seconds) = {
    server
    logger.info(s"Arbitration HitTypeId: ${arbTaskSpec.hitTypeId}")
    startSaves()
    arbActor ! Start(interval, delay = 3 seconds)
  }
  def stop() = {
    arbActor ! Stop
    stopSaves
  }
  def delete() = {
    arbActor ! Delete
  }
  def expire() = {
    arbActor ! Expire
  }
  def update() = {
    server
    arbActor ! Update
  }
  def save() = {
    arbManager ! SaveData
  }

  // for use while it's running. Ideally instead of having to futz around at the console calling these functions,
  // in the future you could have a nice dashboard UI that will help you examine common sources of issues

  def allInfos = alternativePromptReaderOpt match {
    case None =>
      hitDataService.getAllHITInfo[QASRLArbitrationPrompt[SID], QANomResponse](arbTaskSpec.hitTypeId).get
    case Some(altReader) =>
      hitDataService.getAllHITInfo[QASRLArbitrationPrompt[SID], QANomResponse](
        arbTaskSpec.hitTypeId
      )(altReader, implicitly[Reader[QANomResponse]]).get
  }

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


  def printFeedbacks(n: Int = 15) = arbManagerPeek.feedbacks.take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )


  def info(): Unit = {
    val totalPrompts = allPrompts.length * numValidationsForPrompt

    val completedCount = allInfos.map(_.assignments.length).sum
    val uploadedCount = allInfos.length

    println(s"Arbitration HitTypeId: ${arbTaskSpec.hitTypeId}")
    println(s"Active Phase: $stage")
    println()
    println(s"Production Qualification Id: ${productionQualType.getQualificationTypeId}")
    println()
    println(f"Assignments: $completedCount/$totalPrompts (completed / total)")
    println(f"Uploaded generation hits to MTurk: $uploadedCount")
  }
}
