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
  val allNominalPrompts: Vector[QASRLGenerationPrompt[SID]],
//  val allIds: Vector[SID], // IDs of sentences to annotate
  numGenerationAssignmentsInProduction: Int,
  annotationDataService: AnnotationDataService,
  annotationStage: Stage = Stage.Expert,
  sdgenQualTestOpt : Option[QualTest] = None,
  sdvalQualTestOpt : Option[QualTest] = None,
  frozenGenerationHITTypeId: Option[String] = None,
  frozenValidationHITTypeId: Option[String] = None,
  generationAccuracyDisqualTypeLabel: Option[String] = None,
  generationCoverageDisqualTypeLabel: Option[String] = None,
  validationAgreementDisqualTypeLabel: Option[String] = None,
  sdvalidationAgreementDisqualTypeLabel: Option[String] = None,
  sdgenerationTestQualTypeLabel: Option[String] = None,
  sdvalidationTestQualTypeLabel: Option[String] = None)(
  implicit val config: TaskConfig,
  val settings: QASRLSettings,
  val inflections: Inflections
) extends StrictLogging {

  val qasdSettings = QASDSettings.default

  // define numGenerationAssignmentsForPrompt for either production or sandbox
  def numGenerationAssignmentsForPrompt : QASRLGenerationPrompt[SID] => Int =
    if(config.isProduction) (_ => numGenerationAssignmentsInProduction) else (_ => 2) // how many generators?
//    if(config.isProduction) (_ => 2) else (_ => 1) // how many generators?

  // define numValidatorsAssignmentsForPrompt for verbs, for either production or sandbox
  def numValidatorsAssignmentsForPrompt : QASRLValidationPrompt[SID] => Int =
    if(config.isProduction) (_ => 0) else (_ => 0)  // how many validators?

  // define numValidatorsAssignmentsForPrompt for non-verbs, for either production or sandbox
  def numSDValidatorsAssignmentsForPrompt : QASRLValidationPrompt[SID] => Int =
    if(config.isProduction) (_ => 0) else (_ => 0)  // how many sd-validators?

  // collect indices of verbs in the sentence to generate prompt
  def getVerbKeyIndices(id: SID): Set[Int] = {
    val posTaggedTokens = PosTagger.posTag(id.tokens)
    posTaggedTokens.collect {
      case Word(index, pos, token) if PosTags.verbPosTags.contains(pos) =>
        if( // detect if "have"-verb is an auxiliary
          Inflections.haveVerbs.contains(token.lowerCase) &&
            (posTaggedTokens.lift(index + 1).map(_.token.lowerCase).nonEmptyAnd(Inflections.negationWords.contains) || // negation appears directly after, or
               posTaggedTokens.drop(index + 1).forall(_.pos != "VBN") || // there is no past-participle verb afterward, or
               posTaggedTokens.drop(index + 1) // after the "have" verb,
               .takeWhile(_.pos != "VBN") // until the next past-participle form verb,
               .forall(w => Inflections.negationWords.contains(w.token.lowerCase) || PosTags.adverbPosTags.contains(w.pos)) // everything is an adverb or negation (though I guess negs are RB)
            )
        ) None else if( // detect if "do"-verb is an auxiliary
          Inflections.doVerbs.contains(token.lowerCase) &&
            (posTaggedTokens.lift(index + 1).map(_.token.lowerCase).nonEmptyAnd(Inflections.negationWords.contains) || // negation appears directly after, or
               posTaggedTokens.drop(index + 1).forall(w => w.pos != "VB" && w.pos != "VBP") || // there is no stem or non-3rd-person present verb afterward (to mitigate pos tagger mistakes), or
               posTaggedTokens.drop(index + 1) // after the "do" verb,
               .takeWhile(w => w.pos != "VB" && w.pos != "VBP") // until the next VB or VBP verb,
               .forall(w => Inflections.negationWords.contains(w.token.lowerCase) || PosTags.adverbPosTags.contains(w.pos)) // everything is an adverb or negation (though I guess negs are RB)
            )
        ) None else inflections.getInflectedForms(token.lowerCase).map(_ => index)
    }.flatten.toSet
  }

  // collect indices of nouns in the sentence to generate noun-prompt
  def getNounKeyIndices(id: SID): Set[Int] = {
    val posTaggedTokens = PosTagger.posTag(id.tokens)
    posTaggedTokens.collect {
      case Word(index, pos, token) if PosTags.nounPosTags.contains(pos) => index
    }.toSet
  }

  // collect indices of adjectives in the sentence to generate noun-prompt
  def getAdjectiveKeyIndices(id: SID): Set[Int] = {
    val posTaggedTokens = PosTagger.posTag(id.tokens)
    posTaggedTokens.collect {
      case Word(index, pos, token) if PosTags.adjectivePosTags.contains(pos) => index
    }.toSet
  }

  // collect indices of adverbs in the sentence to generate noun-prompt
  def getAdverbKeyIndices(id: SID): Set[Int] = {
    val posTaggedTokens = PosTagger.posTag(id.tokens)
    posTaggedTokens.collect {
      case Word(index, pos, token) if PosTags.adverbPosTags.contains(pos) => index
    }.toSet
  }

  // collect indices of numbers in the sentence to generate noun-prompt
  def getNumberKeyIndices(id: SID): Set[Int] = {
    val posTaggedTokens = PosTagger.posTag(id.tokens)
    posTaggedTokens.collect {
      case Word(index, pos, token) if pos=="CD" => index
    }.toSet
  }


  // Current Nominalizations Experiment: use only verb-generation pipeline, for nominalizations
  lazy val allVerbPrompts = allNominalPrompts
  lazy val allSDPrompts: Vector[QASRLGenerationPrompt[SID]] = Vector.empty

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

  // now combine the current-stage related requirements for the generation HITs
  val stageRelatedRequirements : Array[QualificationRequirement] = annotationStage match {
    case Stage.Trap => Array(nomTrapPhaseRequirement)
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


  // duplicate generation-coverage qualification for non-verbs
  val sdgenCoverageDisqualTypeLabelString = generationCoverageDisqualTypeLabel.fold("")(x => s"[$x] ")
  val sdgenCoverageDisqualTypeName = s"${sdgenCoverageDisqualTypeLabelString} Questions asked per target word disqualification"
  val sdgenCoverageDisqualType = findQualificationType(sdgenCoverageDisqualTypeName).getOrElse{
    logger.info("Generating sdgeneration coverage disqualification type...")
    createQualification(sdgenCoverageDisqualTypeName, "Number of questions asked for each target in our question-answer " +
      "pair generation task is too low.")
  }
  val sdgenCoverageDisqualTypeId = sdgenCoverageDisqualType.getQualificationTypeId
  val sdgenCoverageRequirement = createDisqualificationReq(sdgenCoverageDisqualType)



  val valAgrDisqualTypeLabelString = validationAgreementDisqualTypeLabel.fold("")(x => s"[$x] ")
  val valAgrDisqualTypeName = s"${valAgrDisqualTypeLabelString}Question answering agreement disqualification"
  val valAgrDisqualType = findQualificationType(valAgrDisqualTypeName).getOrElse{
    logger.info("Generating validation disqualification type...")
    createQualification(valAgrDisqualTypeName, "Agreement with other annotators on answers and validity " +
      "judgments in our question answering task is too low.")
  }
  val valAgrDisqualTypeId = valAgrDisqualType.getQualificationTypeId
  val valAgreementRequirement = createDisqualificationReq(valAgrDisqualType)

  // copy last paragraph for sdvalidation qualification
  val sdvalAgrDisqualTypeLabelString = sdvalidationAgreementDisqualTypeLabel.fold("")(x => s"[$x] ")
  val sdvalAgrDisqualTypeName = s"${sdvalAgrDisqualTypeLabelString}Question answering agreement disqualification"
  val sdvalAgrDisqualType = findQualificationType(sdvalAgrDisqualTypeName).getOrElse{
    logger.info("Generating sdvalidation disqualification type...")
    createQualification(sdvalAgrDisqualTypeName, "Agreement with other annotators on answers and validity " +
      "judgments in our question answering task is too low.")
  }
  val sdvalAgrDisqualTypeId = sdvalAgrDisqualType.getQualificationTypeId

  val sdvalAgreementRequirement = createDisqualificationReq(sdvalAgrDisqualType)


  // Optoinal: add Qualification Test as qualification to sdvalidation

  val sdvalTestQualTypeLabelString = sdvalidationTestQualTypeLabel.fold("")(x => s"[$x] ")
  val sdvalTestQualTypeName = if(config.isProduction)
                                s"${sdvalTestQualTypeLabelString}Question answering test score (%)"
                              else "Sandbox sdvalidation test score qual"
  val sdvalTestQualTypeOpt = sdvalQualTestOpt.flatMap(qualTest => Some({
    config.service.listQualificationTypes (
    new ListQualificationTypesRequest ()
      .withQuery (sdvalTestQualTypeName)
      .withMustBeOwnedByCaller (true)
      .withMustBeRequestable (false)
      .withMaxResults(100)
    ).getQualificationTypes.asScala.toList.find(_.getName == sdvalTestQualTypeName).getOrElse {
      System.out.println ("Generating sdvalidation test qualification type...")
      config.service.createQualificationType (
      new CreateQualificationTypeRequest ()
        .withName (sdvalTestQualTypeName)
        .withKeywords ("language,english,question answering")
        .withDescription ("""Score on the qualification test for the question answering task,
                as a test of your understanding of the instructions.""".replaceAll ("\\s+", " ") )
        .withQualificationTypeStatus (QualificationTypeStatus.Active)
        .withRetryDelayInSeconds (300L)
        .withTest (qualTest.testString)
        .withAnswerKey (qualTest.answerKeyString)
        .withTestDurationInSeconds (1200L)
        .withAutoGranted (false)
      ).getQualificationType
    }
  }))
  val sdvalTestQualTypeIdOpt = sdvalTestQualTypeOpt.map(_.getQualificationTypeId)
  val sdvalTestRequirementOpt = sdvalTestQualTypeIdOpt.map(qualTypeId =>
    new QualificationRequirement()
    .withQualificationTypeId(qualTypeId)
    .withComparator("GreaterThanOrEqualTo")
    .withIntegerValues(75)
    .withRequiredToPreview(false)
  )

  // Optoinal: add Qualification Test as qualification to sdgeneration

  val sdgenTestQualTypeLabelString = sdgenerationTestQualTypeLabel.fold("")(x => s"[$x] ")
  val sdgenTestQualTypeName = if(config.isProduction)
    s"${sdgenTestQualTypeLabelString}'Writing and answering questions on a sentence' test score (%)"
  else "Sandbox sdgeneration test score qual"
  val sdgenTestQualTypeOpt = sdgenQualTestOpt.flatMap(qualTest => Some({
    config.service.listQualificationTypes (
      new ListQualificationTypesRequest ()
        .withQuery (sdgenTestQualTypeName)
        .withMustBeOwnedByCaller (true)
        .withMustBeRequestable (false)
        .withMaxResults(100)
    ).getQualificationTypes.asScala.toList.find(_.getName == sdgenTestQualTypeName).getOrElse {
      System.out.println ("Generating sdgeneration test qualification type...")
      config.service.createQualificationType (
        new CreateQualificationTypeRequest ()
          .withName (sdgenTestQualTypeName)
          .withKeywords ("language,english,question answering")
          .withDescription ("""Score on the qualification test for the question writing task,
                as a test of your understanding of the instructions.""".replaceAll ("\\s+", " ") )
          .withQualificationTypeStatus (QualificationTypeStatus.Active)
          .withRetryDelayInSeconds (90L)
          .withTest (qualTest.testString)
          .withAnswerKey (qualTest.answerKeyString)
          .withTestDurationInSeconds (1500L)
          .withAutoGranted (false)
      ).getQualificationType
    }
  }))
  val sdgenTestQualTypeIdOpt = sdgenTestQualTypeOpt.map(_.getQualificationTypeId)
  val sdgenTestRequirementOpt = sdgenTestQualTypeIdOpt.map(qualTypeId =>
    new QualificationRequirement()
      .withQualificationTypeId(qualTypeId)
      .withComparator("GreaterThanOrEqualTo")
      .withIntegerValues(70)
      .withRequiredToPreview(false)
  )


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
      sdgenCoverageDisqualTypeId,
      valAgrDisqualTypeId,
      nomTrapQualTypeId,
      nomTrainingQualTypeId,
      nomProductionQualTypeId,
      sdvalAgrDisqualTypeId) ++ sdvalTestQualTypeIdOpt ++ sdgenTestQualTypeIdOpt

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

  val sdgenHITType = HITType(
    title = s"Write question-answer pairs about a word",
    description = s"""
      Given a sentence containing a single highlighted word,
      Your goals are: (1) write questions about that word which
      are answerable by the sentence and (2) mark their
      respective answers in the sentence.
      Questions must adhere to certain templates,
      provided by autocomplete functionality.
      Bonuses are granted for each generated question.
      Maintain high accuracy to stay qualified.
    """.trim.replace("\\s+", " "),
    reward = qasdSettings.generationReward,
    keywords = "language,english,question answering",
    qualRequirements = Array[QualificationRequirement](
      approvalRateRequirement, localeRequirement, genAccuracyRequirement,
      genAgreementRequirement, sdgenCoverageRequirement
    ) ++ sdgenTestRequirementOpt,
    autoApprovalDelay = 2592000L, // 30 days
    assignmentDuration = 3600L)

  lazy val sdgenAjaxService = new Service[QASRLGenerationAjaxRequest[SID]] {
    override def processRequest(request: QASRLGenerationAjaxRequest[SID]) = request match {
      case QASRLGenerationAjaxRequest(workerIdOpt, QASRLGenerationPrompt(id, verbIndex, verbForm)) =>
        val questionListsOpt = for {
          genManagerP <- Option(sdgenManagerPeek)
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
        val inflectedForms = inflections.getInflectedForms(verbForm.lowerCase)
        QASRLGenerationAjaxResponse(stats, tokens, inflectedForms)
    }
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

  lazy val genAjaxService = new Service[QASRLGenerationAjaxRequest[SID]] {
    override def processRequest(request: QASRLGenerationAjaxRequest[SID]) = request match {
      case QASRLGenerationAjaxRequest(workerIdOpt, QASRLGenerationPrompt(id, verbIndex, verbForm)) =>
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
        QASRLGenerationAjaxResponse(stats, tokens, inflectedForms)
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
    allVerbPrompts.head, "", "", List(""),
    List(QANomResponse(3,true,"expect",
      List( VerbQA(3, "Who expects something?", List(Span(0, 0), Span(2, 2))),
            VerbQA(3, "What does someone expects?", List(Span(4, 15)))))))

  lazy val valTaskSpec = TaskSpecification.NoWebsockets[QASRLValidationPrompt[SID], List[QASRLValidationAnswer], QASRLValidationAjaxRequest[SID]](
    settings.validationTaskKey, valHITType, valAjaxService, Vector(sampleValPrompt),
    taskPageHeadElements = taskPageHeadLinks,
    taskPageBodyElements = taskPageBodyLinks,
    frozenHITTypeId = frozenValidationHITTypeId)

  // Same for SD validation - seperate hitType, taskSpec, AjaxService
  val sdvalHITType = HITType(
    title = s"Answer simple questions about a word in a sentence",
    description = s"""
      Given a sentence with a highlighted word and several questions about it,
      highlight the part of the sentence that answers each question,
      and mark questions that are invalid or redundant.
      Maintain high agreement with others to stay qualified.
    """.trim,
    reward = qasdSettings.validationReward,
    keywords = "language,english,question answering",
    qualRequirements = Array[QualificationRequirement](
      approvalRateRequirement, localeRequirement, sdvalAgreementRequirement) ++ sdvalTestRequirementOpt,
    autoApprovalDelay = 2592000L, // 30 days
    assignmentDuration = 3600L)

  lazy val sdvalAjaxService = new Service[QASRLValidationAjaxRequest[SID]] {
    override def processRequest(request: QASRLValidationAjaxRequest[SID]) = request match {
      case QASRLValidationAjaxRequest(workerIdOpt, id) =>
        val workerInfoSummaryOpt = for {
          valManagerP <- Option(sdvalManagerPeek)
          workerId <- workerIdOpt
          info <- valManagerP.allWorkerInfo.get(workerId)
        } yield info.summary

        QASRLValidationAjaxResponse(workerInfoSummaryOpt, id.tokens)
    }
  }

  lazy val sdsampleValPrompt = QASRLValidationPrompt[SID](
    allNominalPrompts.head, "", "", List(""),
    List(QANomResponse(18,false, "do", List())))

  lazy val sdvalTaskSpec = TaskSpecification.NoWebsockets[QASRLValidationPrompt[SID], List[QASRLValidationAnswer], QASRLValidationAjaxRequest[SID]](
    settings.sdvalidationTaskKey, sdvalHITType, sdvalAjaxService, Vector(sdsampleValPrompt),
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

  // copy for SDValidation - Helper, Manager, ManagerPeek, Actor (Task Manager)
  var sdvalManagerPeek: QASRLValidationHITManager[SID] = null

  lazy val sdvalHelper = new HITManager.Helper(sdvalTaskSpec)
  lazy val sdvalManager: ActorRef = actorSystem.actorOf(
    Props {
      // use same class but override file naming using suffix
      sdvalManagerPeek = new QASRLValidationHITManager(
        sdvalHelper,
        sdvalAgrDisqualTypeId,
        accuracyTracker,
        // sentenceTracker,
        numSDValidatorsAssignmentsForPrompt, // how many validators per HIT?
        if(config.isProduction) 100 else 100,
        qasdSettings,
        "NonVerb")
      sdvalManagerPeek
    })

  lazy val sdvalActor = actorSystem.actorOf(Props(new TaskManager(sdvalHelper, sdvalManager)))

  // Actor for aggregating sdgeneration responses to a single sdvalidation prompt
  var sdgenAggregatorPeek: GenerationAggregationManager[SID] = null

  lazy val sdgenAggregator: ActorRef = actorSystem.actorOf(
    Props {
      sdgenAggregatorPeek = new GenerationAggregationManager[SID](
        genAgreementTracker,
        sdvalHelper,
        sdvalManager,
        numGenerationAssignmentsForPrompt,
        "NonVerb"
      )
      sdgenAggregatorPeek
    })

  val genTaskSpec = TaskSpecification.NoWebsockets[QASRLGenerationPrompt[SID], QANomResponse, QASRLGenerationAjaxRequest[SID]](
    settings.generationTaskKey, genHITType, genAjaxService, allVerbPrompts,
    taskPageHeadElements = taskPageHeadLinks,
    taskPageBodyElements = taskPageBodyLinks,
    frozenHITTypeId = frozenGenerationHITTypeId)

  var genManagerPeek: QASRLGenerationHITManager[SID] = null

  val genHelper = new HITManager.Helper(genTaskSpec)
  val genManager: ActorRef = actorSystem.actorOf(
    Props {
      genManagerPeek = new QASRLGenerationHITManager(
        genHelper,
        valHelper,
        valManager,
        genAggregator,
        accuracyTracker,
        genCoverageDisqualTypeId,
        // sentenceTracker,
        numGenerationAssignmentsForPrompt,
        numValidatorsAssignmentsForPrompt,
        if(config.isProduction) 100 else 100,
        allVerbPrompts.iterator)  // the prompts itarator determines what genHITs are generated
      genManagerPeek
    }
  )
  val genActor = actorSystem.actorOf(Props(new TaskManager(genHelper, genManager)))

  //**** lets try to create another HITType and TaskSepc for SDGen (Sem-Dep-Generation)
  val sdgenTaskSpec = TaskSpecification.NoWebsockets[QASRLGenerationPrompt[SID], QANomResponse, QASRLGenerationAjaxRequest[SID]](
    settings.sdgenerationTaskKey, sdgenHITType, sdgenAjaxService, allSDPrompts,
    taskPageHeadElements = taskPageHeadLinks,
    taskPageBodyElements = taskPageBodyLinks,
    frozenHITTypeId = frozenGenerationHITTypeId)

  var sdgenManagerPeek: QASRLGenerationHITManager[SID] = null

  val sdgenHelper = new HITManager.Helper(sdgenTaskSpec)
  val sdgenManager: ActorRef = actorSystem.actorOf(
    Props {
      sdgenManagerPeek = new QASRLGenerationHITManager(
        sdgenHelper,
        sdvalHelper,
        sdvalManager, // here we pass the valManager to genManager, so that when reviewing assignment it add promtp to valManager
        sdgenAggregator,
        accuracyTracker,
        sdgenCoverageDisqualTypeId,
        // sentenceTracker,
        numGenerationAssignmentsForPrompt,
        numSDValidatorsAssignmentsForPrompt,
        if(config.isProduction) 100 else 3,
        allSDPrompts.iterator, // the prompts itarator determines what genHITs are generated
        qasdSettings,
        "nonVerb")
      sdgenManagerPeek
    }
  )

  val sdgenActor = actorSystem.actorOf(Props(new TaskManager(sdgenHelper, sdgenManager)))

    /** until here is the "copy"- gen to sdgen.  new objects:
      *   genHITType
      *   genTaskSpec
      *   genActor
      *   genHelper
      *   genManager
      *   genManagerPeek
      *   genAjaxService
      */

  lazy val server = new Server(List(genTaskSpec, sdgenTaskSpec, valTaskSpec, sdvalTaskSpec))

  // used to schedule data-saves
  private[this] var schedule: List[Cancellable] = Nil
  def startSaves(interval: FiniteDuration = 5 minutes): Unit = {
    if(schedule.exists(_.isCancelled) || schedule.isEmpty) {
      schedule = List(genManager, sdgenManager, valManager, sdvalManager,
        accuracyTracker, genAgreementTracker, genAggregator, sdgenAggregator).map(actor =>
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
  def setSDGenHITsActiveEach(n: Int) = {
    sdgenManager ! SetNumHITsActive(n)
  }
  def setValHITsActive(n: Int) = {
    valManager ! SetNumHITsActive(n)
  }
  def setSDValHITsActive(n: Int) = {
    sdvalManager ! SetNumHITsActive(n)
  }

  import TaskManager.Message._
  def start(interval: FiniteDuration = 30 seconds) = {
    server
    startSaves()
    genActor ! Start(interval, delay = 2 seconds)
    sdgenActor ! Start(interval, delay = 3 seconds)
    valActor ! Start(interval, delay = 3 seconds)
    sdvalActor ! Start(interval, delay = 3 seconds)
  }
  def stop() = {
    genActor ! Stop
    sdgenActor ! Stop
    valActor ! Stop
    sdvalActor ! Stop
    stopSaves
  }
  def delete() = {
    genActor ! Delete
    sdgenActor ! Delete
    valActor ! Delete
    sdvalActor ! Delete
  }
  def expire() = {
    genActor ! Expire
    sdgenActor ! Expire
    valActor ! Expire
    sdvalActor ! Expire
  }
  def update() = {
    server
    genActor ! Update
    sdgenActor ! Update
    valActor ! Update
    sdvalActor ! Update
  }
  def save() = {
    // sentenceTracker ! SaveData
    accuracyTracker ! SaveData
    genAgreementTracker ! SaveData
    genManager ! SaveData
    sdgenManager ! SaveData
    valManager ! SaveData
    sdvalManager ! SaveData
    genAggregator ! SaveData
    sdgenAggregator ! SaveData
  }

  // for use while it's running. Ideally instead of having to futz around at the console calling these functions,
  // in the future you could have a nice dashboard UI that will help you examine common sources of issues

  def allGenInfos = hitDataService.getAllHITInfo[QASRLGenerationPrompt[SID], QANomResponse](genTaskSpec.hitTypeId).get

  def allSDGenInfos = hitDataService.getAllHITInfo[QASRLGenerationPrompt[SID], QANomResponse](sdgenTaskSpec.hitTypeId).get

  def allValInfos = hitDataService.getAllHITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]](valTaskSpec.hitTypeId).get

  def allSDValInfos = hitDataService.getAllHITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]](sdvalTaskSpec.hitTypeId).get

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
    earnings: Double)

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
      stats: Option[QASRLGenerationWorkerStats],
      genAgrStats: Option[QASRLGenerationWorkerStats],
      info: Option[QASRLValidationWorkerInfo]
    ) = stats.map(_.workerId).orElse(info.map(_.workerId)).map { wid =>
      StatSummary(
        workerId = wid,
        numVerbs = stats.map(_.numAssignmentsCompleted),
        numQs = stats.map(_.numQAPairsWritten),
        accuracy = stats.map(_.accuracy),
        genAgreement = genAgrStats.map(_.genAgreementAccuracy),
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

  def printStats[B : Ordering](sortFn: StatSummary => B) = {
    val summaries = allStatSummaries.sortBy(sortFn)
    printStatsHeading
    summaries.foreach(printSingleStatSummary)
  }

  def printQStats = printStats(-_.numQs.getOrElse(0))
  def printAStats = printStats(-_.numAs.getOrElse(0))

  def printCoverageStats = genManagerPeek.coverageStats.toList
    .sortBy(-_._2.size)
    .map { case (workerId, numQs) => f"$workerId%s\t${numQs.size}%d\t${numQs.sum.toDouble / numQs.size}%.2f" }
    .foreach(println)

  def printGenFeedback(n: Int) = genManagerPeek.feedbacks.take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )
  def printSDGenFeedback(n: Int) = sdgenManagerPeek.feedbacks.take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )
  def printValFeedback(n: Int) = valManagerPeek.feedbacks.take(n).foreach(a =>
    println(a.workerId + " " + a.feedback)
  )

  def printAllFeedbacks(n: Int = Int.MaxValue) = {
    println("Generation (verbs):")
    printGenFeedback(n)
    println("\nGeneration (non-verbs):")
    printSDGenFeedback(n)
    println("\nValidation:")
    printValFeedback(n)
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
}
