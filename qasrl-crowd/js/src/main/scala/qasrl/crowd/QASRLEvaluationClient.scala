package qasrl.crowd

import qasrl.crowd.util.dollarsToCents
import qasrl.crowd.util.MultiContigSpanHighlightableSentenceComponent
import qasrl.crowd.util.Styles
import qasrl.crowd.util.implicits._
import spacro.tasks._
import spacro.ui.AsyncContentComponent

import scala.collection.immutable
//import spacro.ui._
import spacro.util.Span
import cats.implicits._

import nlpdata.util.Text

import scalajs.js
import org.scalajs.dom
import org.scalajs.dom.raw._
import org.scalajs.dom.ext.KeyCode
import org.scalajs.jquery.jQuery

import scala.concurrent.ExecutionContext.Implicits.global

import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._

import upickle.default._

import monocle._
import monocle.function.{all => Optics}
import monocle.macros._
import japgolly.scalajs.react.MonocleReact._

class QASRLEvaluationClient[SID: Writer : Reader](
                                                   instructions: VdomTag)(
                                                   implicit settings: QASRLEvaluationSettings,
                                                   promptReader: Reader[QASRLArbitrationPrompt[SID]], // macro serializers don't work for superclass constructor parameters
                                                   responseWriter: Writer[QANomResponse], // same as above
                                                   ajaxRequestWriter: Writer[QASRLValidationAjaxRequest[SID]] // "
                                                 ) extends TaskClient[QASRLArbitrationPrompt[SID], QANomResponse, QASRLValidationAjaxRequest[SID]] {
  def main(): Unit = jQuery { () =>
    Styles.addToDocument()
    FullUI().renderIntoDOM(dom.document.getElementById(FieldLabels.rootClientDivLabel))
  }

  val AsyncContentComponent = new AsyncContentComponent[QASRLValidationAjaxResponse]
  import AsyncContentComponent._
  import MultiContigSpanHighlightableSentenceComponent._

  lazy val questions : List[String] = prompt.questions
  lazy val proposedAnswers : List[List[Span]] = prompt.qas.map(_.answers)
  lazy val verbForm : String = prompt.genPrompt.verbForm
  lazy val targetIdx : Int = prompt.genPrompt.targetIndex

  val SpanHighlightingComponent = new SpanHighlightingComponent2(proposedAnswers) // question
  import SpanHighlightingComponent._


  @Lenses case class State(
                            isVerbal: Boolean, // whether the target noun is a verbal nominalization
                            curQuestion: Int,
                            isInterfaceFocused: Boolean,
                            answers: List[QASRLValidationAnswer])

  object State {
    // isVerbal starts as true iff both generators agreed it is verbal
    val isVerbalStartingValue : Boolean = prompt.genResponses.forall(_._2.isVerbal)
    def initial = State(isVerbalStartingValue, 0, false, proposedAnswers.map(spans => Answer(spans)))
  }

  def answerSpanOptics(questionIndex: Int) =
    State.answers
      .composeOptional(Optics.index(questionIndex))
      .composePrism(QASRLValidationAnswer.answer)
      .composeLens(Answer.spans)

  class FullUIBackend(scope: BackendScope[Unit, State]) {
    def updateResponse: Callback = scope.state.map { state =>
      // here should send the final consolidated response taken from UI state

      // allQAs have a verbQA for each Q in the input prompt
      val allQAs : List[VerbQA] = state.answers.zipWithIndex.map {case (valAns: QASRLValidationAnswer, idx: Int) =>
        valAns match {
          case Answer(spans) => VerbQA(targetIdx, questions(idx), spans)
          case _ => VerbQA(targetIdx, questions(idx), Nil)
        }}
      // finalQAs are those QAs selected by arbitrator as final annotation
      val finalQAs : List[VerbQA] =
        if (state.isVerbal)
          allQAs.filter(_.answers.nonEmpty)
        else Nil
      val response = QANomResponse(targetIdx, state.isVerbal, verbForm, finalQAs)
      setResponse(response)
    }

    def toggleIsVerbal(buttonIndication: Boolean) : Callback = {
      scope.modState(s => s.copy(isVerbal = buttonIndication))
    }

    def updateCurrentAnswers(highlightingState: SpanHighlightingState) = {
      scope.state >>= (st =>
        scope.modState(
          answerSpanOptics(st.curQuestion).set(highlightingState.spans(st.curQuestion))
        )
        )
    }


    def toggleInvalidAtIndex(highlightedAnswers: Map[Int, Answer])(questionIndex: Int) =
      scope.modState(
        State.answers.modify(answers =>
          answers.updated(
            questionIndex,
            if (answers(questionIndex).isInvalid) highlightedAnswers(questionIndex)
            else InvalidQuestion)
        )
      )

    def toggleRedundantAtIndex(highlightedAnswers: Map[Int, Answer])(questionIndex: Int) = {
      scope.modState(
        State.answers.modify(answers =>
          answers.updated(
            questionIndex,
            if (answers(questionIndex).isRedundant) highlightedAnswers(questionIndex)
            else RedundantQuestion
          )
        ))
    }

    def handleKey(highlightedAnswers: Map[Int, Answer])(e: ReactKeyboardEvent): Callback = {
      def nextQuestion = scope.modState(State.curQuestion.modify(i => (i + 1) % questions.size))

      def prevQuestion = scope.modState(State.curQuestion.modify(i => (i + questions.size - 1) % questions.size))

      def toggleInvalid = scope.zoomStateL(State.curQuestion).state >>= toggleInvalidAtIndex(highlightedAnswers)

      if (isNotAssigned) {
        Callback.empty
      } else CallbackOption.keyCodeSwitch(e) {
        case KeyCode.Up | KeyCode.W => prevQuestion
        case KeyCode.Down | KeyCode.S => nextQuestion
        case KeyCode.Space => toggleInvalid
      } >> e.preventDefaultCB
    }

    def qaField(s: State, sentence: Vector[String], highlightedAnswers: Map[Int, Answer])(index: Int) = {
      val isFocused = s.curQuestion == index
      val answer = s.answers(index)

      <.div(
        ^.overflow := "hidden",
        <.div(
          Styles.unselectable,
          ^.float := "left",
          ^.minHeight := "1px",
          ^.border := "1px solid",
          ^.borderRadius := "2px",
          ^.textAlign := "center",
          ^.width := "55px",
          (^.backgroundColor := "#E01010").when(answer.isInvalid),
          ^.onClick --> toggleInvalidAtIndex(highlightedAnswers)(index),
          "Invalid"
        ),
        <.div(
          Styles.unselectable,
          ^.float := "left",
          ^.minHeight := "1px",
          ^.border := "1px solid",
          ^.borderRadius := "2px",
          ^.textAlign := "center",
          ^.width := "120px",
          (^.backgroundColor := "#FF8700").when(answer.isRedundant),
          ^.onClick --> toggleRedundantAtIndex(highlightedAnswers)(index),
          "Redundant"
        ),
        <.span(
          Styles.bolded.when(isFocused),
          Styles.unselectable,
          ^.float := "left",
          ^.margin := "1px",
          ^.padding := "1px",
          ^.onClick --> scope.modState(State.curQuestion.set(index)),
          questions(index)
        ),
        <.div(
          Styles.answerIndicator,
          Styles.unselectable,
          ^.float := "left",
          ^.minHeight := "1px",
          ^.width := "25px",
          "-->".when(isFocused)
        ),
        <.div(
          ^.float := "left",
          ^.margin := "1px",
          ^.padding := "1px",
          answer match {
            case InvalidQuestion => <.span(
              ^.color := "#CCCCCC",
              "N/A"
            )
            case RedundantQuestion => <.span(
              ^.color := "#CCCCCC",
              "Redundant"
            )
            case Answer(spans) if spans.isEmpty && isFocused =>
              <.span(
                ^.color := "#CCCCCC",
                "Highlight answer above, move with arrow keys or mouse")
            case Answer(spans) if isFocused => // spans nonempty
              (spans.flatMap { span =>
                List(
                  <.span(Text.renderSpan(sentence, (span.begin to span.end).toSet)),
                  <.span(" / ")
                )
              } ++ List(<.span(^.color := "#CCCCCC", "Highlight to add an answer"))).toVdomArray
            case Answer(spans) => spans.map(s => Text.renderSpan(sentence, (s.begin to s.end).toSet)).mkString(" / ")
          }
        )
      )
    }

    def yieldConflictTokens(state: State): Set[Int] = {
      val allSpans = state.answers.flatMap {
        case Answer(spans) => spans
        case InvalidQuestion => List.empty[Span]
        case RedundantQuestion => List.empty[Span]
      }.toVector
      val tokens = allSpans.flatMap(span => span.begin to span.end)

      val conflicts = (for {
        (token, tokenGroup) <- tokens.groupBy(identity)
        if tokenGroup.size > 1
      } yield token)
      conflicts.toSet
    }

    def stylesForConflicts(state: State): Int => TagMod = {
      val curVerbIndex : Int = prompt.qas(state.curQuestion).verbIndex
      val conflicts : Set[Int] = if (state.isVerbal) yieldConflictTokens(state) else Set()

      idx: Int =>
        if (conflicts.contains(idx)) {
          TagMod(Styles.badRed)
        } else {
          TagMod(Styles.specialWord, Styles.niceBlue).when(idx == curVerbIndex)
        }
    }

    private def highlightSpans(state: State, inProgressAnswerOpt: Option[Span]): List[(Span, TagMod)] = {
      val currentAnswers: List[Span] = state.answers(state.curQuestion).getSpans
      val otherAnswers = (for {
        i <- state.answers.indices
        if i != state.curQuestion
        span <- state.answers(i).getSpans
      } yield span).toList

      val inProgress: Option[(Span, TagMod)] = inProgressAnswerOpt.map(_ -> (^.backgroundColor := "#FF8000"))
      val current = currentAnswers.map(_ -> (^.backgroundColor := "#FFFF00"))
      val other = otherAnswers.map(_ -> (^.backgroundColor := "#DDDDDD"))
      val allDone = (current ++ other).map(Some(_))

      // don't highlight no spans when in isVerbal==False state
      if (state.isVerbal)
        (inProgress :: allDone).flatten
      else
        List()
    }

    def isSubmitEnabled(state: State): Boolean = {
      val isComplete = state.answers.forall(_.isComplete)
      val hasConflicts = yieldConflictTokens(state).nonEmpty
      !state.isVerbal || (isComplete && !hasConflicts)
    }

    def render(state: State) = {
      AsyncContent(
        AsyncContentProps(
          getContent = () => makeAjaxRequest(QASRLValidationAjaxRequest(workerIdOpt, prompt.id)),
          render = {
            case Loading => <.div("Retrieving data...")
            case Loaded(QASRLValidationAjaxResponse(workerInfoSummaryOpt, sentence)) =>

              SpanHighlighting(
                SpanHighlightingProps(
                  isEnabled = !isNotAssigned && state.answers(state.curQuestion).isAnswer,
                  enableSpanOverlap = true,
                  update = updateCurrentAnswers,
                  render = {
                    case (hs@SpanHighlightingState(spans, status), SpanHighlightingContext(_, hover, touch, cancelHighlight)) =>

                      val inProgressAnswerOpt = SpanHighlightingStatus.highlighting.getOption(status).map {
                        case Highlighting(_, anchor, endpoint) => Span(anchor, endpoint)
                      }
                      val highlightedAnswers = prompt.qas.indices.map(i =>
                        i -> Answer(spans(i))
                      ).toMap

                      val touchWord = touch(state.curQuestion)
                      <.div(
                        ^.classSet1("container-fluid"),
                        ^.onClick --> cancelHighlight,
                        <.div(
                          instructions,
                          ^.margin := "5px"
                        ),
                        <.div(
                          ^.classSet1("card"),
                          ^.margin := "5px",
                          ^.padding := "5px",
                          ^.tabIndex := 0,
                          ^.onFocus --> scope.modState(State.isInterfaceFocused.set(true)),
                          //^.onBlur --> scope.modState(State.isInterfaceFocused.set(false)),
                          ^.onKeyDown ==> ((e: ReactKeyboardEvent) => handleKey(highlightedAnswers)(e) >> cancelHighlight),
                          ^.position := "relative",
                          <.div(
                            ^.position := "absolute",
                            ^.top := "20px",
                            ^.left := "0px",
                            ^.width := "100%",
                            ^.height := "100%",
                            ^.textAlign := "center",
                            ^.color := "rgba(48, 140, 20, .3)",
                            ^.fontSize := "48pt",
                            (if (isNotAssigned) "Accept assignment to start" else "Click here to start")
                          ).when(!state.isInterfaceFocused),

                          // Sentence
                          MultiContigSpanHighlightableSentence(
                            MultiContigSpanHighlightableSentenceProps(
                              sentence = sentence,
                              styleForIndex = stylesForConflicts(state),
                              highlightedSpans = highlightSpans(state, inProgressAnswerOpt),
                              hover = if (state.isVerbal) hover(state.curQuestion) else (_=>Callback.empty),
                              touch = if (state.isVerbal) touch(state.curQuestion) else (_=>Callback.empty),
                              render = (elements =>
                                <.p(
                                  Styles.largeText,
                                  Styles.unselectable,
                                  elements.toVdomArray)
                                ))
                          ),

                          // Is this a verbal noun?
                          <.div(
                            <.span("Is the highlighted noun a ",
                              <.span(Styles.bolded, "verbal"),
                              " noun?    "),
                            // two buttons - Yes / No
                            <.span(
                              Styles.smallButton,
                              ^.disabled := state.isVerbal,
                              ^.onClick --> toggleIsVerbal(true),
                              (^.backgroundColor := Styles.greenButtonColor).when(state.isVerbal),
                              "Yes"
                            ),
                            <.span(
                              Styles.smallButton,
                              ^.disabled := !state.isVerbal,
                              ^.onClick --> toggleIsVerbal(false),
                              (^.backgroundColor := Styles.redButtonColor).when(!state.isVerbal),
                              "No"
                            ),
                            <.span(Styles.verticalSpace),

                            <.p(Styles.smallerText, """Hint: try to see whether it makes sense to ask verbal questions about it """,
                              """ (see the question box below).""")
                          ),

                          <.div(    // div for all elements that should be hidden when noun not isVerbal
                            <.hr(Styles.verticalSpace),

                            // Show user the verb it is generating questions with
                            <.div(
                              <.p(),
                              <.p(
                                "Ask about the noun '" + Text.normalizeToken(sentence(prompt.targetIndex)) + "' using the verb ",
                                <.span(Styles.verbFormPurple, prompt.verbForm),
                                <.span(":"))
                            ),

                            <.ul(
                              ^.classSet1("list-unstyled"),
                              (0 until questions.size).toVdomArray { index =>
                                <.li(
                                  ^.key := s"question-$index",
                                  ^.display := "block",
                                  qaField(state, sentence, highlightedAnswers)(index))
                              }
                            ),
                            <.p(s"Bonus: ${dollarsToCents(settings.arbitrationBonus(questions.size))}c")

                          ).when(state.isVerbal),

                          // Feedback
                          <.div(
                            ^.classSet1("form-group"),
                            ^.margin := "5px",
                            <.textarea(
                              ^.classSet1("form-control"),
                              ^.name := FieldLabels.feedbackLabel,
                              ^.rows := 3,
                              ^.placeholder := "Feedback? (Optional)"
                            )
                          ),

                          // Submit
                          <.input(
                            ^.classSet1("btn btn-primary btn-lg btn-block"),
                            ^.margin := "5px",
                            ^.`type` := "submit",
                            ^.disabled := !isSubmitEnabled(state),
                            ^.id := FieldLabels.submitButtonLabel,
                            ^.value := (
                              if (isNotAssigned) "You must accept the HIT to submit results"
                              else if (!isSubmitEnabled(state)) "You must resolve all redundancies and respond to all questions to submit results"
                              else "Submit"
                              ))
                        )
                      )
                  }
                )
              )
          }
        )
      )
    }
  }

  val FullUI = ScalaComponent.builder[Unit]("Full UI")
    .initialState(State.initial)
    .renderBackend[FullUIBackend]
    .componentDidUpdate(_.backend.updateResponse)
    .build

}
