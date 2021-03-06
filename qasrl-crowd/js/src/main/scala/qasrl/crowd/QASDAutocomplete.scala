package qasrl.crowd

import qasrl.util.implicits._

import cats.Order
import cats.data.Ior
import cats.data.NonEmptyList

import nlpdata.util.LowerCaseStrings._

import QASDQuestionProcessor.InProgressState
import QASDQuestionProcessor.ValidState

import QASDAutocomplete.Suggestion

class QASDAutocomplete(questionProcessor: QASDQuestionProcessor) {

  private[this] def createSuggestion(ips: InProgressState): Suggestion =
    Suggestion(ips.fullText, questionProcessor.isAlmostComplete(ips))

  private[this] def partitionResults(
                                      goodStates: NonEmptyList[ValidState]
                                    ): Ior[NonEmptyList[Suggestion], NonEmptyList[QASDQuestionProcessor.CompleteState]] =
    goodStates.partition(ValidState.eitherIso.get).leftMap(ipss =>
      ipss.map(createSuggestion).distinct.sorted
    )

  def getSuggestions(relevantQuestions : List[String], commonPrefix : String) : List[Suggestion] = {
    /*
      logic of partial-suggestions - suggest only next word at a time,
      if there are more than one optional continuation
       */
    if (relevantQuestions.size > 1) {

      // create a map {postfix : full-available-Q}
      val postfix2question = relevantQuestions.map(s => (s.substring(commonPrefix.size), s)).toMap
      // create a list of pairs (first-word-in-postfix, full-question-starting-with-it)
      val first_fullQ = postfix2question.toList.map(p_q => (p_q._1.split(" ")(0), p_q._2))
      // create a map {first-word-in-postfix : List of full-questions-starting-with-it}
      val first2Qs =
        first_fullQ
          .map(x => x._1) // iterate firstWords
          .map(firstWord =>
          firstWord -> first_fullQ.collect {
            case fw_q if fw_q._1 == firstWord => fw_q._2
          }
        ).toMap
      /*
    for each prefix in map:
      If it only have single full-question, suggest it.
      If it prefixes multiple questions, suggest an incomplete suggestion with the first word of the postfix
     */
      val questionSuggestions : List[Suggestion] =
        (for ((firstWord, questionList) <- first2Qs
              if (!firstWord.isEmpty) )
        yield
          if (questionList.size <= 1)
            Suggestion(questionList(0), true)
          else
            Suggestion(commonPrefix + firstWord + " ", false)
          ).toList
      /*
       for the case where the commonPrefix is right before a space in (some) question,
       (that is, a "" firstWord exist in the first2Qs map), we want to add suggestions that
       are accesible after the space.
        */
      val afterSpaceSuggestions : List[Suggestion] = {
        if (first2Qs.contains(""))
          getSuggestions(relevantQuestions, commonPrefix + " ")
        else List[Suggestion]()
      }
      return (questionSuggestions ++ afterSpaceSuggestions)
        .sortBy(s => s.isComplete)
    }
    else {
      return for (q <- relevantQuestions) yield Suggestion(q, false)
    }
  }

  def apply(
             question: LowerCaseString,
             completeQuestions: Set[QASDQuestionProcessor.CompleteState]
           ): QASDAutocomplete.Result = {
    val allLowercaseQuestionStrings = completeQuestions.map(_.fullText.lowerCase).toSet
    questionProcessor.processStringFully(question) match {
      case Left(invalidState) =>
//        partitionResults(lastGoodStates).swap.toEither.fold(
//          completeStates => Autocomplete.incomplete(NonEmptyList.of(Suggestion(completeStates.head.fullText, true)), Some(badStartIndex)),
//          suggestions => Autocomplete.incomplete(suggestions, Some(badStartIndex))
//        )
        // suggest Qs using longest common prefix to current question and any of the templates
        val commonPrefix = questionProcessor.getCommonPrefix
        val availableQs = questionProcessor.getAvailableQsFrom(commonPrefix)
        val suggestions = getSuggestions(availableQs, questionProcessor.getCommonPrefix)
        val badIndexStart = commonPrefix.size
        QASDAutocomplete.incomplete(suggestions.toList, Some(badIndexStart))  // my invalid state is incomplete with no suggestion

      case Right(goodStates) =>

//        val framesWithAnswerSlots = completeQuestions.map { state =>
//          state.frame -> state.answerSlot
//        }
//
//        val framesByCountDecreasing = framesWithAnswerSlots
//          .map(_._1).groupBy(identity).map(p => p._1 -> p._2.size)
//          .toVector.sortBy { case (frame, count) => -10 * count + math.abs(frame.args.size - 2) }
//          .map(_._1)
//
//        val frameToFilledAnswerSlots = framesWithAnswerSlots
//          .foldLeft(Map.empty[Frame, Set[ArgumentSlot]].withDefaultValue(Set.empty[ArgumentSlot])) {
//            case (acc, (frame, slot)) => acc.updated(frame, acc(frame) + slot)
//          }
//
//        val allQuestions = framesByCountDecreasing.flatMap { frame =>
//          val unAnsweredSlots = frame.args.keys.toSet -- frameToFilledAnswerSlots(frame)
//          val coreArgQuestions = unAnsweredSlots.toList.flatMap(frame.questionsForSlot)
//          val advQuestions = ArgumentSlot.allAdvSlots
//            .filterNot(frameToFilledAnswerSlots(frame).contains)
//            .flatMap(frame.questionsForSlot)
//          coreArgQuestions ++ advQuestions
//        }.filter(_.toLowerCase.startsWith(question.toLowerCase))
//          .filterNot(q => allLowercaseQuestionStrings.contains(q.lowerCase))
//          .distinct
//
//        val questionSuggestions = allQuestions.flatMap(q =>
//          questionProcessor.processStringFully(q) match {
//            case Right(goodStates) if goodStates.exists(_.isComplete) => Some(Suggestion(q, true))
//            case _ => None
//          }
//        ).take(4) // number of suggested questions capped at 4 to filter out crowd of bad ones

        val questionSuggestions = getSuggestions(questionProcessor.getAvailableQs, questionProcessor.getCommonPrefix)

        partitionResults(goodStates).toEither.fold(
          suggestions => QASDAutocomplete.incomplete(
//            NonEmptyList.fromList(questionSuggestions).fold(suggestions)(sugg =>
//              (sugg ++ suggestions.toList).distinct.sorted
//            ),
            //questionSuggestions.map(str => Suggestion(str, true)),
            questionSuggestions,
            None
          ),
          completeStates => QASDAutocomplete.complete(completeStates.toList.toSet)
        )
    }
  }
}

object QASDAutocomplete {
  sealed trait Result
  case class Incomplete(
                         suggestions: List[Suggestion],
                         badStartIndexOpt: Option[Int]
                       ) extends Result
  case class Complete(
                       completionFrames: Set[QASDQuestionProcessor.CompleteState]
                     ) extends Result

  def incomplete(
                  suggestions: List[Suggestion],
                  badStartIndexOpt: Option[Int]
                ): Result = Incomplete(suggestions, badStartIndexOpt)
  def complete(completionFrames: Set[QASDQuestionProcessor.CompleteState]): Result =
    Complete(completionFrames)

  case class Suggestion(fullText: String, isComplete: Boolean)
  object Suggestion {
    implicit val autocompleteSuggestionOrder: Order[Suggestion] = new Order[Suggestion] {
      override def compare(x: Suggestion, y: Suggestion) =
        if(x.isComplete == y.isComplete) {
          x.fullText.compare(y.fullText)
        } else if(x.isComplete) -1 else 1
    }
  }
}
