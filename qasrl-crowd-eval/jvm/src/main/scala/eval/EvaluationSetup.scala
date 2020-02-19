package eval

import java.nio.file.{Files, Path, Paths}

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.typesafe.scalalogging.StrictLogging
import example.Tokenizer
import nlpdata.datasets.wiktionary
import nlpdata.util.HasTokens
import nlpdata.util.HasTokens.ops._
import nlpdata.util.HasTokens
import qasrl.crowd._
import spacro.HITInfo
import spacro.tasks.TaskConfig
import spacro.util.Span

import scala.collection.immutable
import scala.util.Try
import scala.collection.JavaConverters._

case class QANomRow[SID](sentenceId: SID, verbIndex: Int,
                         isVerbal: Boolean, verbForm: String,
                         question: String, answers: List[Span],
                         assignId: String, workerId: String)

class EvaluationSetup(generationQanomAnnotPath: Path,
                      sentencesPathOpt: Option[Path],
                      liveDataPath: Path,
                      phase: Stage,
                      numEvaluationAssignmentsForPrompt: Int)(
                       implicit config: TaskConfig) extends StrictLogging {

  val resourcePath = java.nio.file.Paths.get("datasets")
  val staticDataPath = Paths.get(s"data/static")

  implicit val liveAnnotationDataService = new FileSystemAnnotationDataService(liveDataPath)

  def saveOutputFile(name: String, contents: String): Try[Unit] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    val directory = path.getParent
    if (!Files.exists(directory)) {
      Files.createDirectories(directory)
    }
    Files.write(path, contents.getBytes())
  }

  def loadOutputFile(name: String): Try[List[String]] = Try {
    val path = staticDataPath.resolve("out").resolve(name)
    import scala.collection.JavaConverters._
    Files.lines(path).iterator.asScala.toList
  }

  def decodeAnswerRange(answerRange: String): List[Span] = {
    val encodedSpans: Vector[String] =
      if (answerRange.isEmpty) Vector() else answerRange.split("~!~").toVector
    val spans = encodedSpans.map(encSpan => {
      val splits = encSpan.split(':').toVector
      // this project uses exclusive indices
      Span(splits(0).toInt, splits(1).toInt - 1)
    })
    spans.toList
  }

  def getQANomResponses(verbIdx: Int, qaRows: Vector[QANomRow[SentenceId]]): List[(String, QANomResponse)] = {
    for {
      (workerId, workerRows) <- qaRows.groupBy(row => row.workerId).toList
    } yield (workerId, getQANomResponse(verbIdx, workerRows))
  }

  def getQANomResponse(verbIdx: Int, qaRows: Vector[QANomRow[SentenceId]]): QANomResponse = {
    val firstRow = qaRows(0)
    val qas = if (firstRow.question.isEmpty) Nil else getVerbQAs(verbIdx, qaRows)
    QANomResponse(verbIdx, firstRow.isVerbal, firstRow.verbForm, qas)
  }

  def getVerbQAs(verbIdx: Int, qaRows: Vector[QANomRow[SentenceId]]): List[VerbQA] = {
    val sortedQas = qaRows.sortBy(_.question.split(" ")(0)) // sort by WH-word
    val qas = for {
      qa <- sortedQas
    } yield VerbQA(verbIdx, qa.question, qa.answers)
    qas.toList
  }

  def getArbitrationPrompts(qaPairsCsvPath: Path, sentenceDataset: Map[String, Vector[String]]):
  Vector[QASRLArbitrationPrompt[SentenceId]] = {
    val allRecords : List[Map[String, String]] = CSVReader.open(qaPairsCsvPath.toString).allWithHeaders()
    val qaPairs = (for {
      rec <- allRecords
      sentId = rec("qasrl_id")
      sent = sentenceDataset(sentId)
      verbIdx = rec("verb_idx").toInt
      assignId = rec("assign_id")
      workerId = rec("worker_id")
      isVerbal = rec("is_verbal").toBoolean
      verbForm = rec("verb_form")

      question = rec("question")
      answerRanges = rec("answer_range")
      spans = decodeAnswerRange(answerRanges)
    } yield new QANomRow[SentenceId](SentenceId(sentId), verbIdx, isVerbal, verbForm, question, spans, assignId, workerId)).toVector

    val allArbPrompts = for {
      (key, qaGroup) <- qaPairs.groupBy(qa => (qa.sentenceId, qa.verbIndex))
      (sentId, verbIdx) = key

      genWorkerId2qanomResponses = getQANomResponses(verbIdx, qaGroup)
      verbForm = genWorkerId2qanomResponses.head._2.verbForm
      genPrompt = QANomGenerationPrompt[SentenceId](sentId, verbIdx, verbForm)
    } yield QASRLArbitrationPrompt[SentenceId](genPrompt, genWorkerId2qanomResponses)

    // Additional filtering:  filter out prompts for which all generators agreed target is not verbal
//    val arbPrompts = allArbPrompts.filter(_.genResponses.exists(_._2.isVerbal))

    // Stronger Additional filtering:  filter out prompts for which all generators agreed no-QA-Applicable (or non-verbal)
    val arbPrompts = allArbPrompts.filter(_.genResponses.exists(_._2.qas.nonEmpty))

    arbPrompts.toVector
  }

  val sentenceDataset: Map[String, Vector[String]] = {
    val (sourceCsvPath, sentColName) = sentencesPathOpt match {
      // if desired, can load sentences from an external CSV file (and not from generationAnnot)
      case Some(sentencesPath) => (sentencesPath, "tokens")
      // Otherwise, can load the sentence information from generationAnnot CSV - should have a 'sentence' column
      case None => (generationQanomAnnotPath, "sentence")
    }

    logger.info(s"Reading sentenceDataset from: $sourceCsvPath")
    val reader: CSVReader = CSVReader.open(sourceCsvPath.toString)
    // CSV format:
    // qasrl_id, tokens, sentence
    // 10_13ecbplus.xml_0,Report : Red Sox offer Teixeira $ 200 million,Report: Red Sox offer Teixeira $200 million
    val csvRecords = reader.allWithHeaders()
    (for {
      rec <- csvRecords
      id = rec("qasrl_id")
      tokens: Vector[String] = rec(sentColName).split(" ").toVector
    } yield id -> tokens).toMap
  }

  val allIds: Vector[SentenceId] = sentenceDataset.keys.map(SentenceId(_)).toVector

  lazy val Wiktionary = new wiktionary.WiktionaryFileSystemService(
    resourcePath.resolve("wiktionary")
  )

  implicit object SentenceIdHasTokens extends HasTokens[SentenceId] {
    override def getTokens(sid: SentenceId): Vector[String] = sentenceDataset(sid.id)
  }

  implicit lazy val inflections = {
    val tokens = for {
      id <- allIds.iterator
      word <- id.tokens.iterator
    } yield word
    Wiktionary.getInflectionsForTokens(tokens)
  }

  // use qasrlPath as CSV Path for QA pairs
  val allPrompts: Vector[QASRLArbitrationPrompt[SentenceId]] = getArbitrationPrompts(generationQanomAnnotPath, sentenceDataset)
  val experiment = new QASRLEvaluationPipeline[SentenceId](
    allPrompts,
    numEvaluationAssignmentsForPrompt,
    phase)

  val exp = experiment

  def saveArbitrationData(filename: String,
                          arbInfos: List[HITInfo[QASRLArbitrationPrompt[SentenceId], QANomResponse]]): Unit = {
    val contents: List[QANom] = DataIO.makeArbitrationQAPairTSV(SentenceId.toString, arbInfos).toList
    val path = liveDataPath.resolve(filename).toString
    val csv = CSVWriter.open(path, encoding = "utf-8")
    csv.writeRow(DataIO.qasrlColumns)
    for (qasrl <- contents) {
      // will iterate in order over the case class fields
      csv.writeRow(qasrl.productIterator.toList)
    }
  }

}
