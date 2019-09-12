package example

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
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

import java.nio.file.{Files, Path, Paths}

import scala.sys.process._
import scala.language.postfixOps
import scala.util.Try
import scala.util.Random
import upickle.default._
import io.circe

case class PromptData(sentence_id : String,
                      tokenized_sentence : Vector[String],
                      nominal_index : Int,
                      verb_form : String)

class AnnotationSetup(
  val label: String = "trial",
  val executePreprocessing : Boolean = true,
  frozenGenerationHITTypeId: Option[String] = None,
  frozenValidationHITTypeId: Option[String] = None)(
  implicit config: TaskConfig) {

  val datasetsPath = java.nio.file.Paths.get("datasets")
  val resourcePath = java.nio.file.Paths.get("resources")
  val scriptsPath = java.nio.file.Paths.get("scripts")

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

  /*
  Data Route:
  1. raw input - csv file with sentences + sentence-Ids
  2. pre-process with generate_nom_candidates.py script - get candidate noms in a json format
        output of script is in resources/nom_prompts.json
  3. read resources/nom_prompts.json for list of prompts
   */

  // take raw sentences from this file. Use Python script
  val input_sentences_fn = "source.txt"
  val raw_data_path : Path = resourcePath.resolve(input_sentences_fn)

  val preprocess_script_fn = scriptsPath.resolve("generate_nom_candidates.py")
  val prompts_data_fn = "nom_prompts.json"  // output of preprocess script, input for server
  val prompts_data_path = resourcePath.resolve(prompts_data_fn)

  // exec command for running the python script (Otherwise, assume the JSON is already ready)
  if (executePreprocessing) {
    val preprocess_cmd = s"python ${preprocess_script_fn} ${raw_data_path} ${prompts_data_path}"
    val exec_result: Int = Process(preprocess_cmd).!
    assert(exec_result == 0, "pre-processing script failed")
  }

/* Take prompt data from json file including the nom-derivation info */
  val prompts_json_raw : String = loadInputFile(prompts_data_fn).get.mkString("\n")
  val prompts_json_data : circe.Json = circe.parser.parse(prompts_json_raw).getOrElse(circe.Json.Null)


  def decode_prompt(prompt_json : circe.Json) : PromptData = {
    val prompt_map : Map[String, circe.Json]= prompt_json.asObject.get.toMap
    val sent_id : String = prompt_map("sentenceId").asString.get
    val tok_sent : Vector[String] = prompt_map("tokSent").asArray.get.flatMap(_.asString)
    val nominal_index : Int = prompt_map("targetIdx").asNumber.get.toInt.get
    val verb_forms : Vector[String] = prompt_map("verbForms").asArray.get.flatMap(_.asString)
    val verb_form : String = if (verb_forms.nonEmpty) verb_forms(0) else ""
    PromptData(sent_id, tok_sent, nominal_index, verb_form)
  }

  val prompts_data : Vector[PromptData] = prompts_json_data.asArray.get.map(decode_prompt)
  val tokenizedSentences = prompts_data.map(_.tokenized_sentence)
  val sentences = tokenizedSentences.map(s_vec => s_vec.mkString(" "))
  // Instead of:
  //val tokenizedSentences = sentences.map(Tokenizer.tokenize_with_ner)

  // save mapping between sentence_IDs to sentences (tokenized)
  val sentenceIdToTokens : Map[String, Vector[String]] = prompts_data.map(
    p => p.sentence_id -> p.tokenized_sentence).toMap

  def promptDataToPrompt(pd : PromptData) : QASRLGenerationPrompt[SentenceId] = {
    QASRLGenerationPrompt(SentenceId(pd.sentence_id), pd.nominal_index, pd.verb_form)
  }
  val allNominalPromptsFromInput : Vector[QASRLGenerationPrompt[SentenceId]] = {
    prompts_data.map(promptDataToPrompt)
  }

//  val posTaggedSentences = tokenizedSentences.map(PosTagger.posTag[Vector](_))

  val numOfSentences = tokenizedSentences.size
  val allIds = sentenceIdToTokens.keys.map(SentenceId(_)).toVector
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
    override def getTokens(id: SentenceId): Vector[String] = sentenceIdToTokens(id.id)
  }

  implicit lazy val inflections = {
    val tokens = for {
      id <- allIds.iterator
      word <- id.tokens.iterator
    } yield word
    Wiktionary.getInflectionsForTokens(tokens)
  }

  val numGenerationAssignmentsInProduction = 8   // how many generators?

  // filter nominal prompts - exclude those that have no inflected forms for the verb-form,
  // since the qasrl state-machine cannot support it
  def hasVerbalInflection(prompt : QASRLGenerationPrompt[SentenceId]) : Boolean = {
    inflections.hasInflectedForms(prompt.verbForm.lowerCase)
  }

  val (allNominalPrompts, illegalNominalPrompts) = allNominalPromptsFromInput.partition(hasVerbalInflection)
  // log (print) excluded prompts
  println("\n*********************")
  println(s"Prompts that have no inflected forms for neither verb-form (excluded): ${illegalNominalPrompts.size}")
  println(illegalNominalPrompts)
  println("*********************\n")


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

  // Saving the crowd annotations (generation)
  // from Pavel
  val qasrlColumns = List(
    "qasrl_id", "sentence", "verb_idx", "verb",
    "worker_id", "assign_id", "source_assign_id",
    "is_verbal", "verb_form",
    "question", "is_redundant", "answer_range", "answer",
    "wh", "subj", "obj", "obj2", "aux", "prep", "verb_prefix",
    "is_passive", "is_negated")

  def saveGenerationData(
                          filename: String,
                          genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], QANomResponse]]
                        ): Unit = {
    val contents = DataIO.makeGenerationQAPairTSV(SentenceId.toString, genInfos)
    val path = liveDataPath.resolve(filename).toString
    val csv = CSVWriter.open(path, encoding = "utf-8")
    csv.writeRow(qasrlColumns)
    for (qasrl <- contents) {
      // will iterate in order over the case class fields
      csv.writeRow(qasrl.productIterator.toList)
    }
  }



  def saveAnnotationData[A](
    filename: String,
    ids: Vector[SentenceId],
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], QANomResponse]],
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
    genInfos: List[HITInfo[QASRLGenerationPrompt[SentenceId], QANomResponse]],
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
