package eval

import spacro.ui._

import qasrl.crowd.QASRLEvaluationDispatcher
import qasrl.crowd.util._

import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._

import scalajs.js.JSApp

import upickle.default._

object Dispatcher extends QASRLEvaluationDispatcher[SentenceId] {

  val dataToggle = VdomAttr("data-toggle")
  val dataPlacement = VdomAttr("data-placement")

  val TooltipsComponent = ScalaComponent.builder[VdomTag]("Tooltips")
    .render(_.props)
    .componentDidMount(_ =>
    Callback {
      scala.util.Try {
        scala.scalajs.js.Dynamic.global.$("[data-toggle=\"tooltip\"]").tooltip()
      }
      ()
    }
  ).build

  import settings._

  def example(question: String, answer: String, isGood: Boolean, tooltip: String = "") =
    <.li(
      <.span(
        if(isGood) Styles.goodGreen else Styles.badRed,
        TagMod(
          Styles.underlined,
          dataToggle := "tooltip",
          dataPlacement := "top",
          ^.title := tooltip).when(tooltip.nonEmpty),
        <.span(question),
        <.span(" --> "),
        <.span(answer)
      )
    )

  import InstructionsComponent._

  val arbitrationInstructionSlides = "https://docs.google.com/presentation/d/1ECharO3EKCabVDx_PYVUDfbdgYJ0uKu5sRo165OSNXM/present?slide=id.p"
  val generationSlides = "https://docs.google.com/presentation/d/1AGLdjilE4GDaF1ybXaS4JXabGLrfK58W1p6mteU_yrw/present?slide=id.p"

  val arbitrationOverview = <.div(
    <.p("In this task you will help us validate questions and answers generated by several annotators from previous rounds. " +
      "You will be presented with a sentence, ", <.span(Styles.niceBlue, "a highlighted noun "), " and possibly a list of questions and answers collected by several " +
      "annotators, during the ", <.span(Styles.bolded ,"write question-answer pairs about verbal nouns "), " task. "),

    <.p("Your task is to ensure the quality of the annotation decisions, and consolidate the questions and answers into a single set."),
    <.p("For a complete set of instructions please refer to this link: ",
      <.a(^.href := arbitrationInstructionSlides, ^.target.blank, ^.rel := "noopener",
        <.span(Styles.bolded, " Guidelines for Annotators in Consolidating Questions and Answers"))),
    <.ul(
      <.li("Mark ", <.span(Styles.badRed, "Invalid"), " questions that are unrelated to the target, are non-grammatical, or that have no direct answer in the text."),
      <.li("Identify groups of questions that ask about the same thing."),
      <.li("Select the most appropriate question (the most natural phrasing in English) for each sub-group."),
      <.li("Add all correct answers available in the sentence. Answers from other questions should help you identify possible candidates."),
      <.li("Mark the other ", <.span(Styles.bolded, "valid"), " but redundant questions as ", <.span(Styles.uncomfortableOrange, Styles.bolded, "Redundant"), ". This will unmark their answers and remove overlaps from the sentence."),
      <.li("You can and should add new answers, delete or modify existing answers, according to the guidelines in the generation task."),
      <.li("Make sure to split an answer if you identify that the shorter phrases can be featured as standalone answers by themselves."),
      <.li("There should not be any overlap between words in the answers. ", <.span(Styles.badRed, "The conflicting words will be indicated in RED "), "in the sentence. " +
        "Please resolve the conflicts by either marking redundant questions or modifying answers to the best of your ability.")

  ),
    <.p("The final output should ", <.span(Styles.bolded, "strictly "), "follow ",
      <.a(^.href := generationSlides, ^.target.blank, ^.rel := "noopener",
        <.span(Styles.bolded, " the original guidelines")),", and be as close as possible to the output produced by expert annotators."),
    <.p("As always, the expert annotator team will be sampling your work and providing you with feedback.")
  )

  val arbitrationConditions = <.div(
    <.p(s"""You will be paid a bonus of ${dollarsToCents(arbitrationBonusPerQuestion)}c
        for every question beyond the first ${arbitrationBonusThreshold}, which will be paid when the assignment is approved.
        Your work will be sampled and checked with our expert annotator team.""",
        <.p("""Your work will be approved and the bonus will be paid within an hour.""")
    )
  )

  val validationOverview = <.div(
    <.p(Styles.badRed, """Read through all of the instructions and make sure you understand the interface controls before beginning. A full understanding of the requirements will help maximize your agreement with other workers so you can retain your qualification."""),
    <.p(s"""This task is for an academic research project by the natural language processing group at the University of Washington.
           We wish to deconstruct the meanings of English sentences into lists of questions and answers.
           You will be presented with a selection of English text and a list of questions prepared by other annotators."""),
    <.p("""You will highlight the words in the sentence that correctly answer each question,
           as well as mark whether questions are invalid.""",
      <.b(""" Note: it takes exactly 2 clicks to highlight each answer; see the Controls tab for details. """),
        """For example, consider the following sentence:"""),
    <.blockquote(
      ^.classSet1("blockquote"),
      "Protesters ", <.span(Styles.bolded, " blamed "), " the corruption scandal on local officials, who today ",
      " refused to promise that they would resume the investigation before year's end. "),
    <.p("""You should choose all of the following answers:"""),
    <.ul(
      <.li("Who blamed someone? --> ", <.span(Styles.goodGreen, " Protesters ")),
      <.li("Who did someone blame something on? --> ", <.span(Styles.goodGreen, " local officials / they")),
      <.li("What did someone blame on someone? --> ", <.span(Styles.goodGreen, " the corruption scandal"))),
    <.p(s"""You will be paid a ${dollarsToCents(validationBonusPerQuestion)}c bonus per question after the first $validationBonusThreshold questions if there are more than $validationBonusThreshold."""),
    <.h2("""Guidelines"""),
    <.ol(
      <.li(
        <.span(Styles.bolded, "Correctness. "),
        """Each answer must satisfy the litmus test that if you substitute it back into the question,
           the result is a grammatical statement, and it is true according to the sentence given. For example, """,
        <.span(Styles.bolded, "Who blamed someone? --> Protesters"), """ becomes """,
        <.span(Styles.goodGreen, "Protesters blamed someone, "), """ which is valid, while """,
        <.span(Styles.bolded, "Who blamed? --> Protesters"), """ would become """,
        <.span(Styles.badRed, "Protesters blamed, "), s""" which is ungrammatical, so it is invalid.
           Your responses will be compared to other annotators, and you must agree with them
           ${(100.0 * validationAgreementBlockingThreshold).toInt}% of the time in order to remain qualified. """),
      <.li(
        <.span(Styles.bolded, "Verb-relevance. "),
        """ Answers to the questions must pertain to the participants, time, place, reason, etc., of """,
        <.span(Styles.bolded, " the target verb in the sentence, "),
        " which is bolded and colored blue in the interface. ",
        """ For example, if the sentence is """,
        <.span(Styles.bolded,
               " He ",
               <.span(Styles.niceBlue, Styles.underlined, "promised"),
               " to come tomorrow "),
        """ and the question is """,
        <.span(Styles.badRed, " When did someone promise to do something? "),
        """ you must mark it """,
        <.span(Styles.badRed, " Invalid "),
        """ because the time mentioned, """, <.i(" tomorrow, "), " is ", <.i(" not "),
        " the time that he made the promise, but rather the time that he might come."),
      <.li(
        <.span(Styles.bolded, "Exhaustiveness. "),
        s"""You must provide every possible answer to each question.
           When highlighting answers, please only include the necessary words to provide a complete, grammatical answer,
           but if all else is equal, prefer to use longer answers.
           Also please include pronouns in the sentence that refer an answer you've already given.
           However, note that none of the answers to your questions may overlap.
           If the only possible answers to a question were already used for previous questions, please mark it invalid."""
      )
    ),
    <.p(" All ungrammatical questions should be counted invalid. However, ",
        " If the sentence has grammatical errors or is not a complete sentence, please answer ",
        " questions according to the sentence's meaning to the best of your ability. "),
    <.p("Please read through the examples if you need more details.")
  )

  val validationConditions = <.div(
    <.p(s"""You will be paid a bonus of ${dollarsToCents(validationBonusPerQuestion)}c
        for every question beyond $validationBonusThreshold, which will be paid when the assignment is approved.
        Your judgments will be cross-checked with other workers,
        and your agreement rate will be shown to you in the interface.
        If this number drops below ${(100 * validationAgreementBlockingThreshold).toInt}
        you will no longer qualify for the task.
        (Note that other validators will sometimes make mistakes,
        so there is an element of randomness to it: don't read too deeply into small changes in your agreement rate.)
        Your work will be approved and the bonus will be paid within an hour.""")
  )


  override val evaluationInstructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "validationCollapseCookie",
        tabs = List(
          "Overview" -> arbitrationOverview,
          "Conditions & Payment" -> arbitrationConditions
        )
      )
    )
  )
}
