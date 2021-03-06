package qasrl.crowd

import qasrl.crowd.util.implicits._

import spacro.util.Span

import cats.implicits._

import nlpdata.util.Text

import monocle._
import monocle.macros._

/** Represents a validator response about a question:
  * either it has an answer, or is invalid
  */
sealed trait QASRLValidationAnswer {
  def isInvalid = this match {
    case InvalidQuestion => true
    case _ => false
  }

  def isRedundant = this match {
    case RedundantQuestion => true
    case _ => false
  }

  def getAnswer = this match {
    case a @ Answer(_) => Some(a)
    case _ => None
  }
  def isAnswer = getAnswer.nonEmpty
  def isValid = !isInvalid

  def isComplete = this match {
    case InvalidQuestion => true
    case RedundantQuestion => true
    case Answer(indices) => indices.nonEmpty
  }

  def agreesWith(that: QASRLValidationAnswer) = (this, that) match {
    case (InvalidQuestion, InvalidQuestion) => true
    case (RedundantQuestion, RedundantQuestion) => true
      // Currently not handling linking a RedundantQuestion to it's paraphrased Question;
      // todo take linkage into account of agreement
    case (Answer(spans1), Answer(spans2)) =>
      spans1.exists(span1 =>
        spans2.exists(span2 =>
          (span1.begin to span1.end).toSet.intersect((span2.begin to span2.end).toSet).nonEmpty
        )
      )
    // Redundant is also agreeing with Answer (since it is considering the question valid)
    case (RedundantQuestion, Answer(spans)) => true
    case (Answer(spans), RedundantQuestion) => true
    case _ => false
  }
}
case object InvalidQuestion extends QASRLValidationAnswer
case object RedundantQuestion extends QASRLValidationAnswer
@Lenses case class Answer(spans: List[Span]) extends QASRLValidationAnswer

object QASRLValidationAnswer {
  val invalidQuestion = GenPrism[QASRLValidationAnswer, InvalidQuestion.type]
  val redundantQuestion = GenPrism[QASRLValidationAnswer, RedundantQuestion.type]
  val answer = GenPrism[QASRLValidationAnswer, Answer]

  def validQuestions(allQuestions : List[String], responses: List[List[QASRLValidationAnswer]]) : List[String] = {
    // return the set of valid questions from valPrompt.
    // allQuestion is the the list of questions, and each response is same-order list of QASRLValidationAnswer
    // that corresponds to the questions.
    val numOfValidators = responses.size.toFloat
    def proportionValidated(index : Int) = responses.count(valAnswers => {
      valAnswers(index).isValid
    }) / numOfValidators
    val validPropostionThreshold = 0.5

    // return: valid question (check using index)
    allQuestions.zipWithIndex.filter(question_and_index =>
      proportionValidated(question_and_index._2) >= validPropostionThreshold
    ).map(_._1)
  }
  
  // render a validation answer for the purpose of writing to a file
  // (just writes the highlighted indices of the answer; not readable)
  def renderIndices(
    va: QASRLValidationAnswer
  ): String = va match {
    case InvalidQuestion => "Invalid"
    case RedundantQuestion => "Redundant"
    case Answer(spans) => spans.map { case Span(begin, end) => s"$begin-$end" }.mkString(" / ")
  }

  // inverse of QASRLValidationAnswer.renderIndices
  def readIndices(
    s: String
  ): QASRLValidationAnswer = s match {
    case "Invalid" => InvalidQuestion
    case "Redundant" => RedundantQuestion
    case other => Answer(
      other.split(" / ").toList.map(is =>
        is.split("-").map(_.toInt).toList match {
          case begin :: end :: Nil => Span(begin, end)
          case _ => ??? // should not happen
        }
      )
    )
  }

  // render a validation response in a readable way for browsing
  def render(
    sentence: Vector[String],
    va: QASRLValidationAnswer
  ): String = va match {
    case InvalidQuestion => "<Invalid>"
    case RedundantQuestion => "<Redundant>"
    case Answer(spans) => spans.map(span => Text.renderSpan(sentence, (span.begin to span.end).toSet)).mkString(" / ")
  }
}
