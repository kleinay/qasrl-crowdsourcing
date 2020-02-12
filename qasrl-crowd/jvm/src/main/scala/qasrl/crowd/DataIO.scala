package qasrl.crowd

import qasrl.Frame
import qasrl.QuestionProcessor
import qasrl.TemplateStateMachine
import qasrl.labeling.{QuestionLabelMapper, SlotBasedLabel}
import qasrl.crowd.util.CategoricalDistribution
import qasrl.crowd.util.implicits._
import cats.Foldable
import cats.data.NonEmptyList
import cats.implicits._
import spacro.{Assignment, HITInfo}
import spacro.util.Span
import nlpdata.datasets.wiktionary.Inflections
import nlpdata.datasets.wiktionary.InflectedForms
import nlpdata.util.HasTokens.ops._
import nlpdata.util.LowerCaseStrings._
import nlpdata.util.{HasTokens, LowerCaseStrings, Text}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable
import scala.reflect.macros.whitebox

case class QANom(sid: String, sentence: String, verbIdx: Int, key:String, target: String,
                 workerId: String, assignId: String, sourceWorkerId: String,
                 isVerbal: Boolean, verbForm: String,
                 question: String, isRedundant: Boolean, answerRanges: String, answers: String,
                 wh: String, subj: String, obj: String, obj2: String,
                 aux: String, prep: String, verbPrefix: String,
                 isPassive: Boolean, isNegated: Boolean)

object DataIO extends LazyLogging {

  val qasrlColumns = List(
    "qasrl_id", "sentence", "verb_idx", "key", "verb",
    "worker_id", "assign_id", "source_worker_ids",
    "is_verbal", "verb_form",
    "question", "is_redundant", "answer_range", "answer",
    "wh", "subj", "obj", "obj2", "aux", "prep", "verb_prefix",
    "is_passive", "is_negated")

  private def idAndTarget[SID](r: HITInfo[QANomGenerationPrompt[SID], QANomResponse]): (SID, Int) = {
    (r.hit.prompt.id, r.hit.prompt.targetIndex)
  }

  private def getText(span: Span, tokens: Vector[String]) = {
    tokens.slice(span.begin, span.end + 1).mkString(" ")
  }

  private def getRangeAsText(span: Span) = {
    s"${span.begin}:${span.end + 1}"
  }

  def genAssignmentToQANomTSVRows[SID: HasTokens](genAssignment: Assignment[QANomResponse],
                                                  sid : SID,
                                                  idString: String,
                                                  targetIndex : Int,
                                                  verbForm: String)(
                                                   implicit inflections: Inflections) : Iterable[QANom] = {
    val sTokens = sid.tokens
    val target = sTokens(targetIndex).lowerCase
    val sentence = sTokens.mkString(" ")
    val workerId = genAssignment.workerId
    val assignId = genAssignment.assignmentId
    // gen response info
    val qas : List[VerbQA]= genAssignment.response.qas
    val isVerbal = genAssignment.response.isVerbal
    val verbForm = genAssignment.response.verbForm

    // if not verbal, or isVerbal but no-QA-Applicable - yield a single row with empty question & answer
    if (!isVerbal || qas.isEmpty) {
      Vector(QANom(idString, sentence, targetIndex, idString +"_"+ targetIndex.toString, target,
        workerId, assignId, "",
        isVerbal, verbForm,
        "", false, "", "",
        "", "", "", "",
        "", "", "",
        false, false))

    } else for {
      // yield a row for each question
      inflForms: InflectedForms <- inflections.getInflectedForms(verbForm.lowerCase).toList
      verbQA: VerbQA <- qas

      question = verbQA.question
      // take the question string without the '?' character. Last token might be a preposition.
      // We will not identify it if it contains '?' character
      prepositions: Set[LowerCaseString]
      = getAllPrepositions(question)
      stateMachine = new TemplateStateMachine(sTokens, inflForms, Some(prepositions))
      template = new QuestionProcessor(stateMachine)
      goodStatesOpt = template.processStringFully(question).toOption
      slotOpt
        <- SlotBasedLabel.getSlotsForQuestion(sTokens, inflForms, List(question))
      slot
        <- slotOpt
      goodStates
        <- goodStatesOpt
      frame: Frame
      = goodStates.toList.collect {
        case QuestionProcessor.CompleteState(_, someFrame, _) => someFrame
      }.head
    } yield {
      val subj = slot.subj.getOrElse("".lowerCase)
      val aux = slot.aux.getOrElse("".lowerCase)
      val verbPrefix = slot.verbPrefix.mkString("~!~")
      val obj = slot.obj.getOrElse("".lowerCase)
      val prep = slot.prep.getOrElse("".lowerCase)
      val obj2 = slot.obj2.getOrElse("".lowerCase)
      val answerRanges = verbQA.answers.map(getRangeAsText).mkString("~!~")
      val answers = verbQA.answers.map(getText(_, sTokens)).mkString("~!~")
      QANom(idString, sentence, targetIndex, idString +"_"+ targetIndex.toString, target,
        workerId, assignId, "",
        isVerbal, verbForm,
        question, false, answerRanges, answers,
        slot.wh, subj, obj, obj2,
        aux, prep, verbPrefix,
        frame.isPassive, frame.isNegated)
    }
  }

  private def idAndTargetArbitration[SID](r: HITInfo[QASRLArbitrationPrompt[SID], QANomResponse]): (SID, Int) = {
    (r.hit.prompt.genPrompt.id, r.hit.prompt.genPrompt.targetIndex)
  }

  def makeGenerationQAPairTSV[SID: HasTokens](
                                               writeId: SID => String, // serialize sentence ID for distribution in data file
                                               genInfos: List[HITInfo[QANomGenerationPrompt[SID], QANomResponse]])(
                                               implicit inflections: Inflections): Iterable[QANom] = {

    for {
      ((sid, targetIndex), hitInfos) <- genInfos.groupBy(idAndTarget)
      idString = writeId(sid)
      HITInfo(genHIT, genAssignments) <- hitInfos.sortBy(_.hit.prompt.targetIndex)
      verbForm = genHIT.prompt.verbForm
      genAssignment <- genAssignments.sortBy(_.workerId)
      qanomRow <- genAssignmentToQANomTSVRows(genAssignment, sid, idString, targetIndex, verbForm)
    } yield qanomRow
  }

  def makeArbitrationQAPairTSV[SID: HasTokens](
                                                writeId: SID => String, // serialize sentence ID for distribution in data file
                                                arbInfos: List[HITInfo[QASRLArbitrationPrompt[SID], QANomResponse]])(
                                                implicit inflections: Inflections): Iterable[QANom] = {
    for {
      ((sid, targetIndex), hitInfos) <- arbInfos.groupBy(idAndTargetArbitration)
      idString = writeId(sid)
      sTokens = sid.tokens
      HITInfo(arbHit, arbAssignments) <- hitInfos
      target = sTokens(targetIndex).lowerCase
      sentence = sTokens.mkString(" ")
      predicate_key = idString +"_"+ targetIndex.toString

      arbAssignment <- arbAssignments.sortBy(_.workerId)

      workerId = arbAssignment.workerId
      assignId = arbAssignment.assignmentId
      qanomConsolidatedResponse : QANomResponse = arbAssignment.response
      isVerbal = qanomConsolidatedResponse.isVerbal
      verbForm : String = qanomConsolidatedResponse.verbForm
      // take inflectedForms from verbForm (from genPrompt or response) and not from target!
      inflForms: InflectedForms <- inflections.getInflectedForms(verbForm.lowerCase).toList
      sourceWorkerIds : String = arbHit.prompt.genResponses.map(_._1).mkString("~!~")

      qa : VerbQA <- qanomConsolidatedResponse.qas
      question = qa.question
      answerSpans : List[Span] = qa.answers
      answerTexts = answerSpans.map(getText(_, sTokens)).mkString("~!~")
      answerRanges = answerSpans.map(getRangeAsText).mkString("~!~")
      // take the question string without the '?' character. Last token might be a preposition.
      // We will not identify it if it contains '?' character
      prepositions: Set[LowerCaseString] = getAllPrepositions(question)
      stateMachine = new TemplateStateMachine(sTokens, inflForms, Some(prepositions))
      template = new QuestionProcessor(stateMachine)
      goodStatesOpt = template.processStringFully(question).toOption
      slotOpt <- SlotBasedLabel.getSlotsForQuestion(sTokens, inflForms, List(question))
      slot <- slotOpt
      goodStates <- goodStatesOpt
      frame: Frame = goodStates.toList.collect {
        case QuestionProcessor.CompleteState(_, someFrame, _) => someFrame
      }.head
    } yield {
      val subj = slot.subj.getOrElse("".lowerCase)
      val aux = slot.aux.getOrElse("".lowerCase)
      val verbPrefix = slot.verbPrefix.mkString("~!~")
      val obj = slot.obj.getOrElse("".lowerCase)
      val prep = slot.prep.getOrElse("".lowerCase)
      val obj2 = slot.obj2.getOrElse("".lowerCase)

      QANom(idString, sentence, targetIndex, predicate_key, target,
        workerId, assignId, sourceWorkerIds,
        isVerbal, verbForm,
        question,
        false, // isRedundant- not informative
        answerRanges, answerTexts,
        slot.wh, subj, obj, obj2,
        aux, prep, verbPrefix,
        frame.isPassive, frame.isNegated)
    }
  }

  // take arbitration infos and generate their original generation-infos csv. Use for re-annotating some arb-infos.
  def makeOrigGenerationQAPairTSV[SID: HasTokens](
                                                   writeId: SID => String, // serialize sentence ID for distribution in data file
                                                   arbInfos: List[HITInfo[QASRLArbitrationPrompt[SID], QANomResponse]])(
                                               implicit inflections: Inflections): Iterable[QANom] = {

    for {
      ((sid, targetIndex), arbHitInfos) <- arbInfos.groupBy(idAndTargetArbitration)
      idString = writeId(sid)
      HITInfo(arbHIT, arbAssignments) <- arbHitInfos.sortBy(_.hit.prompt.targetIndex)
      arbPrompt = arbHIT.prompt
      genPrompt = arbPrompt.genPrompt
      verbForm = genPrompt.verbForm
      (gen_wid, genResponse) <- arbPrompt.genResponses
      genAssignment = Assignment[QANomResponse]("", "", "", gen_wid, 0, 0, genResponse, "")
      qanomRow <- genAssignmentToQANomTSVRows(genAssignment, sid, idString, targetIndex, verbForm)
    } yield qanomRow
  }

  private def getAllPrepositions[SID: HasTokens](question: String) = {
    val qTokens = question.init.split(" ").toVector.map(_.lowerCase)
    val qPreps = qTokens.filter(TemplateStateMachine.allPrepositions.contains).toSet
    val qPrepBigrams = qTokens.sliding(2)
      .filter(_.forall(TemplateStateMachine.allPrepositions.contains))
      .map(_.mkString(" ").lowerCase)
      .toSet
    val prepositions = qPreps ++ qPrepBigrams
    prepositions
  }

  /*
  def makeQAPairTSV[SID: HasTokens, QuestionLabel](
                                                    ids: List[SID],
                                                    writeId: SID => String, // serialize sentence ID for distribution in data file
                                                    genInfos: List[HITInfo[QASRLGenerationPrompt[SID], QANomResponse]],
                                                    valInfos: List[HITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]]],
                                                    mapLabels: QuestionLabelMapper[String, QuestionLabel],
                                                    renderLabel: QuestionLabel => String)(
                                                    implicit inflections: Inflections
                                                  ): String = {
    val genInfosBySentenceId = genInfos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
    val valInfosByGenAssignmentId = valInfos.groupBy(_.hit.prompt.sourceAssignmentId).withDefaultValue(Nil)
    val sb = new StringBuilder
    for (id <- ids) {
      val idString = writeId(id)
      val sentenceTokens = id.tokens
      val sentenceSB = new StringBuilder
      var shouldIncludeSentence = false // for now, print everything
      sentenceSB.append(s"${idString}\t${sentenceTokens.mkString(" ")}\n")
      for {
        // sort by keyword group first...
        HITInfo(genHIT, genAssignments) <- genInfosBySentenceId(id).sortBy(_.hit.prompt.verbIndex)
        // then worker ID second, so the data will be chunked correctly according to HIT;
        genAssignment <- genAssignments.sortBy(_.workerId)
        // process in order of verb
        verbIndex = genAssignment.response.verbIndex
        // only use verbs where we have inflected forms (should always be the case though)
        inflForms <- inflections.getInflectedForms(genHIT.prompt.verbForm.lowerCase).toList
        verbQAsIndexed = genAssignment.response.qas.zipWithIndex.filter(_._1.verbIndex == verbIndex)
        labels = mapLabels(sentenceTokens, inflForms, verbQAsIndexed.map(_._1.question))
        ((wqa, qaIndex), Some(qLabel)) <- verbQAsIndexed.zip(labels)
      } yield {
        // pairs of (validation worker ID, validation answer)
        val valAnswerSpans = for {
          info <- valInfosByGenAssignmentId.get(genAssignment.assignmentId).getOrElse(Nil)
          assignment <- info.assignments
          answer <- assignment.response(qaIndex).getAnswer
        } yield answer.spans
        if (valAnswerSpans.size != 2) {
          logger.warn("Warning: don't have 2 validation answers for question. Actual number: " + valAnswerSpans.size)
        } else {
          shouldIncludeSentence = true
          sentenceSB.append("\t")
          sentenceSB.append(wqa.verbIndex.toString + "\t")
          sentenceSB.append(s"turk-${genAssignment.workerId}" + "\t")
          sentenceSB.append(renderLabel(qLabel) + "\t")
          sentenceSB.append(
            (wqa.answers :: valAnswerSpans).map { spans =>
              spans
                .map(span => s"${span.begin}-${span.end}")
                .mkString(";")
            }.mkString("\t")
          )
          sentenceSB.append("\n")
        }
      }
      if (shouldIncludeSentence) {
        sb.append(sentenceSB.toString)
      }
    }
    sb.toString
  }

   */

  // how much and how long must come first so we register them as question prefix
  val whPhrases = List("how much", "how long", "who", "what", "when", "where", "why", "how").map(_.lowerCase)

//  def makeReadableQAPairTSV[SID: HasTokens](
//                                             ids: List[SID],
//                                             writeId: SID => String, // serialize sentence ID for distribution in data file
//                                             anonymizeWorker: String => String, // anonymize worker IDs so they can't be tied back to workers on Turk
//                                             genInfos: List[HITInfo[QASRLGenerationPrompt[SID], List[VerbQA]]],
//                                             valInfos: List[HITInfo[QASRLValidationPrompt[SID], List[QASRLValidationAnswer]]],
//                                             keepQA: (SID, VerbQA, List[QASRLValidationAnswer]) => Boolean = (
//                                               (_: SID, _: VerbQA, _: List[QASRLValidationAnswer]) => true)
//                                           ): String = {
//    val genInfosBySentenceId = genInfos.groupBy(_.hit.prompt.id).withDefaultValue(Nil)
//    val valInfosByGenAssignmentId = valInfos.groupBy(_.hit.prompt.sourceAssignmentId).withDefaultValue(Nil)
//    val sb = new StringBuilder
//    for (id <- ids) {
//      val idString = writeId(id)
//      val sentenceTokens = id.tokens
//      val sentenceSB = new StringBuilder
//      var shouldIncludeSentence = false
//      sentenceSB.append(s"${idString}\t${nlpdata.util.Text.render(sentenceTokens)}\n")
//      // sort by keyword group first...
//      for (HITInfo(genHIT, genAssignments) <- genInfosBySentenceId(id).sortBy(_.hit.prompt.verbIndex)) {
//        // then worker ID second, so the data will be chunked correctly according to HIT;
//        for (genAssignment <- genAssignments.sortBy(_.workerId)) {
//          // and these should already be ordered in terms of the target word used for a QA pair.
//          for ((wqa, qaIndex) <- genAssignment.response.zipWithIndex) {
//            // pairs of (validation worker ID, validation answer)
//            val valResponses = valInfosByGenAssignmentId.get(genAssignment.assignmentId).getOrElse(Nil)
//              .flatMap(_.assignments.map(a => (a.workerId, a.response(qaIndex))))
//            if (valResponses.size != 2) {
//              logger.warn("Warning: don't have 2 validation answers for question. Actual number: " + valResponses.size)
//            }
//            val valAnswers = valResponses.map(_._2)
//
//            if (keepQA(id, wqa, valAnswers)) {
//              shouldIncludeSentence = true
//              sentenceSB.append(anonymizeWorker(genAssignment.workerId) + "\t") // anonymized worker ID
//              sentenceSB.append(s"${Text.normalizeToken(sentenceTokens(wqa.verbIndex))} (${wqa.verbIndex})\t")
//              sentenceSB.append(wqa.question + "\t") // question string written by worker
//              sentenceSB.append(
//                ((Answer(wqa.answers)) :: valResponses.map(_._2)).map { valAnswer =>
//                  QASRLValidationAnswer.render(sentenceTokens, valAnswer)
//                }.mkString("\t")
//              )
//              sentenceSB.append("\n")
//            }
//          }
//        }
//      }
//      if (shouldIncludeSentence) {
//        sb.append(sentenceSB.toString)
//      }
//    }
//    sb.toString
//  }
}
