package qasrl.crowd

//import nlpdata.structure._
import cats.data.NonEmptyList
import cats.data.EitherT
import cats.implicits._

import monocle.Iso

import nlpdata.util.LowerCaseStrings._

class QASDQuestionProcessor(sentence : String, targetWord : String, targetType : String) {

  import QASDQuestionProcessor._

  val questions : QASDQuestions = targetType match {
    case "noun" => QASDQuestions(targetWord, QASDNounTemplates.list)
    case "adjective" => QASDQuestions(targetWord, QASDAdjectiveTemplates.list)
    case "adverb" => QASDQuestions(targetWord, QASDAdverbTemplates.list)
  }

  var state : String = "" // the state of current question- the text inserted so far by user

  def changeState(newState : String) : Unit = {
    state = newState.toLowerCase
  }

  // what subset of qlist is available from a prefix
  def getAvailableQsFrom(prefix : String) : List[String] = {
    questions.qlist filter (_.toLowerCase.startsWith(prefix))
  }

  // what subset of qlist is available from current state
  def getAvailableQs : List[String] = {
    getAvailableQsFrom(state)
  }

  // is there any possible question available for state
  def isValid : Boolean = {
    !getAvailableQs.isEmpty
  }

  // does state corresponds to an entrance of possibleQs
  def isComplete : Boolean = {
    questions.qlist.map(_.toLowerCase).contains(state)
  }

  // return the longest prefix of state that is shared with at least one possible question
  def getCommonPrefix : String = {
    var pref = ""
    for (ch <- state) {
      val new_pref = pref + ch
      if (getAvailableQsFrom(new_pref).isEmpty)
        return pref
      pref = new_pref
    }
    state
  }
  // ***** here is copied API from qasrl.QuestionProcessor *****
  // public - this is used by Client
  // my main change- this return a single state (Invalid or Valid)
  def processStringFully(input: String): Either[InvalidState, NonEmptyList[ValidState]] = {
    changeState(input)
    if (!isValid)
      Left(InvalidState())
    else if (isComplete)
      Right(NonEmptyList.of(CompleteState(state)))
    else {
      /*
      current state have several possible suggestions.
      return list of inProgressState with possible completions available from current state
       */
      val possibleStates = for (avblQ <- getAvailableQs) yield InProgressState(avblQ)
      Right(NonEmptyList.fromList(possibleStates).get)
    }
  }

  def isAlmostComplete(ips : InProgressState) : Boolean = {
    getAvailableQs.size == 1
  }


}

object QASDQuestionProcessor {

  case class InvalidState()

  sealed trait ValidState {
    def fullText : String
    def isComplete: Boolean
  }
  object ValidState {
    def eitherIso: Iso[ValidState, Either[InProgressState, CompleteState]] =
      Iso[ValidState, Either[InProgressState, CompleteState]](
        vs => vs match {
          case ips: InProgressState => Left(ips)
          case cs: CompleteState => Right(cs)
        })(
        eith => eith match {
          case Left(ips) => ips: ValidState
          case Right(cs) => cs: ValidState
        }
      )
  }

  case class CompleteState(override val fullText : String)
    extends ValidState {
    override def isComplete = true
  }

  case class InProgressState(override val fullText : String)
    extends ValidState {
    override def isComplete = false
  }

}

