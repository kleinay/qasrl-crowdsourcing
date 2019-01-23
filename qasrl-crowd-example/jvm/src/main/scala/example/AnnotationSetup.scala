package example

import cats._
import cats.implicits._
import qasrl.crowd.util.Tokenizer
import qasrl.crowd.util.PosTagger
import qasrl.crowd.{QASRLGenerationPrompt, _}
import qasrl.labeling._
import spacro._
import spacro.tasks._
import nlpdata.structure.AlignedToken
import nlpdata.datasets.wiki1k.Wiki1kFileSystemService
import nlpdata.datasets.wiki1k.Wiki1kPath
import nlpdata.datasets.wiktionary
import nlpdata.datasets.wiktionary.Inflections
import nlpdata.datasets.tqa.TQAFileSystemService
import nlpdata.util.LowerCaseStrings._
import nlpdata.util.Text
import nlpdata.util.HasTokens
import nlpdata.util.HasTokens.ops._
import akka.actor._
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import upickle.default._
import java.io.StringReader
import java.nio.file.{Files, Path, Paths}

import scala.util.Try
import scala.util.Random
import upickle.default._
import io.circe

case class PromptData(tokenized_sentence : Vector[String],
                      nominal_index : Int,
                      verb_form : String)

class AnnotationSetup(
  val label: String = "trial",
  frozenGenerationHITTypeId: Option[String] = None,
  frozenValidationHITTypeId: Option[String] = None)(
  implicit config: TaskConfig) {

  val datasetsPath = java.nio.file.Paths.get("datasets")
  val resourcePath = java.nio.file.Paths.get("resources")

  import java.nio.file.{Paths, Path, Files}
  private[this] val liveDataPath = Paths.get(s"data/example/$label/live")
  val liveAnnotationDataService = new FileSystemAnnotationDataService(liveDataPath)

  val staticDataPath = Paths.get(s"data/example/$label/static")

  def saveOutputFile(name: String, contents: String): Try[Unit] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    val directory = path.getParent
    if(!Files.exists(directory)) {
      Files.createDirectories(directory)
    }
    Files.write(path, contents.getBytes())
  }

  def loadOutputFile(name: String): Try[List[String]] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    import scala.collection.JavaConverters._
    Files.lines(path).iterator.asScala.toList
  }

  def loadInputFile(fileName: String): Try[List[String]] = Try {
    val path = resourcePath.resolve(fileName)
    import scala.collection.JavaConverters._
    Files.lines(path).iterator.asScala.toList
  }

//  val data_fn = "source.txt"
//  val sentences = loadInputFile(data_fn).get.toVector

/* Take prompt data from json file including the nom-derivation info */
  val data_fn = "nom_prompts.json"
  val input_file_data : String = loadInputFile(data_fn).get.mkString("\n")
  val prompts_json_data = circe.parser.parse(input_file_data).getOrElse(circe.Json.Null)


  def decode_prompt(prompt_json : circe.Json) : PromptData = {
    val prompt_arr = prompt_json.asArray.get
    val tok_sent : Vector[String] = prompt_arr(0).asArray.get.flatMap(_.asString)
    val nominal_index : Int = prompt_arr(1).asNumber.get.toInt.get
    val verb_form : String = prompt_arr(2).asString.get
    PromptData(tok_sent, nominal_index, verb_form)
  }

  val prompts_data : Vector[PromptData] = prompts_json_data.asArray.get.map(decode_prompt)
  val tokenizedSentences = prompts_data.map(_.tokenized_sentence)
  val sentences = tokenizedSentences.map(s_vec => s_vec.mkString(" "))
  // Instead of:
  //val tokenizedSentences = sentences.map(Tokenizer.tokenize_with_ner)

  val allNominalPrompts : Vector[QASRLGenerationPrompt[SentenceId]] =
    prompts_data.zipWithIndex.map(item => item match { case (promptData, idx) =>
      QASRLGenerationPrompt(SentenceId(idx), promptData.nominal_index, promptData.verb_form)})

  val posTaggedSentences = tokenizedSentences.map(PosTagger.posTag[Vector](_))

  val numOfSentences = tokenizedSentences.size
  val allIds = (0 until numOfSentences).map(SentenceId(_)).toVector
  val trainIds = allIds.slice(0, numOfSentences / 2)
  val devIds = allIds.slice(numOfSentences / 2, numOfSentences / 4 * 3)
  val testIds = allIds.slice(numOfSentences / 4 * 3, numOfSentences)

  def isTrain(sid: SentenceId) = trainIds.contains(sid)
  def isDev(sid: SentenceId) = devIds.contains(sid)
  def isTest(sid: SentenceId) = testIds.contains(sid)

  lazy val Wiktionary = new wiktionary.WiktionaryFileSystemService(
    datasetsPath.resolve("wiktionary")
  )

  implicit object SentenceIdHasTokens extends HasTokens[SentenceId] {
    override def getTokens(id: SentenceId): Vector[String] = tokenizedSentences(id.index)
  }

  implicit lazy val inflections = {
    val tokens = for {
      id <- allIds.iterator
      word <- id.tokens.iterator
    } yield word
    Wiktionary.getInflectionsForTokens(tokens)
  }

  val numGenerationAssignmentsInProduction = 8   // how many generators?

  lazy val experiment = new QASRLAnnotationPipeline(
    allNominalPrompts,
    numGenerationAssignmentsInProduction,
    liveAnnotationDataService,
    //sdgenQualTestOpt = Some(SDGenQualTestExample),
    sdgenQualTestOpt = None,
    //sdvalQualTestOpt = Some(SDValQualTestExample),
    sdvalQualTestOpt = None,
    frozenGenerationHITTypeId = frozenGenerationHITTypeId,
    frozenValidationHITTypeId = frozenValidationHITTypeId,
    generationAccuracyDisqualTypeLabel = None,
    generationCoverageDisqualTypeLabel = None,
    validationAgreementDisqualTypeLabel = None,
    sdvalidationAgreementDisqualTypeLabel = Some("Non-verb"))

  def saveAnnotationData[A](
    filename: String,
    ids: Vector[SentenceId],
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
    valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]],
    labelMapper: QuestionLabelMapper[String, A],
    labelRenderer: A => String
  ) = {
    saveOutputFile(
      s"$filename.tsv",
      DataIO.makeQAPairTSV(
        ids.toList,
        SentenceId.toString,
        genInfos,
        valInfos,
        labelMapper,
        labelRenderer)
    )
  }

  def saveAnnotationDataReadable(
    filename: String,
    ids: Vector[SentenceId],
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], List[VerbQA]]],
    valInfos: List[HITInfo[QASRLValidationPrompt[SentenceId], List[QASRLValidationAnswer]]]
  ) = {
    saveOutputFile(
      s"$filename.tsv",
      DataIO.makeReadableQAPairTSV(
        ids.toList,
        SentenceId.toString,
        identity,
        genInfos,
        valInfos,
        (id: SentenceId, qa: VerbQA, responses: List[QASRLValidationAnswer]) => responses.forall(_.isAnswer))
    )
  }
}
