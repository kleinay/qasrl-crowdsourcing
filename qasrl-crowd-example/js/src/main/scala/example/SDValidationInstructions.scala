package example

import spacro.ui._

import qasrl.crowd.QASRLDispatcher
import qasrl.crowd.util._

import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._

import scalajs.js.JSApp

import upickle.default._


object SDValidationInstructions extends Instructions {

  import qasrl.crowd.QASDSettings.default._
  import InstructionsComponent._


  val sdvalidationOverview = <.div(
    <.p(Styles.badRed, """Read through all of the instructions below, and make sure you understand the task and interface controls before beginning. A full understanding of the requirements will help maximize your agreement with other workers so you can retain your qualification."""),
    <.p("""This task is for an academic research project of natural language processing.
          We wish to deconstruct the meanings of English sentences into relations between words in the sentence."""),
    <.span("You will be presented with a selection of English text with a designated "),
    <.span(Styles.niceBlue, Styles.underlined, "target word"),
    <.span(", and a list of questions prepared by other annotators regarding that word. "),
    <.span("""Your task is to highlight the words in the sentence that correctly answer each question,
           as well as mark whether questions are invalid, or redundant with respect to previous questions.""",
      """ You will highlight an answer span using """,
      <.b("""exactly 2 clicks """), <.span(
        """- one click on the first word of the answer, and one click on the last word
          (see the Controls tab for further details). """)),
    <.p("""For example, consider the following sentence:"""),
    <.blockquote(
      ^.classSet1("blockquote"),
      "During the 1730s Britain 's ", <.span(Styles.bolded, " relationship ")," with Spain had slowly declined . "),
    <.p("""You should highlight the following answers:"""),
    <.ul(
      <.li("Whose relationship? --> ", <.span(Styles.goodGreen, " Britain 's ")),
      <.li("What is the relationship with? --> ", <.span(Styles.goodGreen, " Spain "))),
    <.p(s"""You will be paid a ${dollarsToCents(validationBonusPerQuestion)}c bonus per question after the first $validationBonusThreshold questions if there are more than $validationBonusThreshold."""),
    <.h2("""Guidelines"""),
    <.ol(
      <.li(
        <.span(Styles.bolded, "Correctness. "),
        """Each answer must be a true answer to the question according to the sentence given.
           You should be strict with regard to what can be asserted from the sentence. For example, """,
        <.span(Styles.bolded, "When was the relationship? --> During the 1730s"),
        s""" is invalid, since the answer phrase is not referring to the time of the relationship, but to the time of it's decline.
        Your responses will be compared to other annotators, and you must agree with them
           ${(100.0 * validationAgreementBlockingThreshold).toInt}% of the time in order to remain qualified. """),
      <.li(
        <.span(Styles.bolded, "Target-relevance. "),
        """ The question should be interpreted as referring """,
        <.span(Styles.bolded, " the target word in the sentence, "),
        " which is bolded and colored blue in the interface. ",
        """ For example, if the sentence is """,
        <.span(Styles.bolded,
          " Using this new format, one can combine many MP3 ",
          <.span(Styles.niceBlue, Styles.underlined, "files"),
          " into one or two large files"),
        """, and the question is """,
        <.span(Styles.goodGreen, " How many files? "),
        """, you should mark """,
        <.span(Styles.goodGreen, " many "),
        """, but not """,
        <.span(Styles.badRed, " one or two "),
        """, because the question is only referring the highlighted occurrence of 'files'."""),
      <.li(
        <.span(Styles.bolded, "Uniqueness. "),
        s"""Some question may have the same meaning or even the exact same phrasing as other questions in your prompt.
            After answering the first question, you should mark the following same-meaning questions as """,
            <.span(Styles.redundantOrange, "Redundant"), """.
            A question is considered redundant if the answers you would have answered it are
            identical to the answers of a previously answered question."""
      ),
      <.li(
        <.span(Styles.bolded, "Exhaustiveness. "),
        s"""You must provide every possible answer to each question.
           Also please include pronouns in the sentence that refer an answer you've already given.
           However, note that none of the answers to your questions may overlap.
           If the only possible answers to a question were already used for previous questions, please mark it invalid."""
      ),
      <.li(
        <.span(Styles.bolded, "Short Answers. "),
        s"""When highlighting answers, prefer to use shorter answers, ideally a single word or a common phrase."""
      )
    ),
    <.p(" The questions were composed automatically and selected by annotators to describe ",
      " the relation between the target word and the answer spans. ",
      " Thus, questions might suffer some grammatical flaws, but should be nevertheless ",
      " considered as valid, ",
      <.span(Styles.bolded, "as long as you can clearly understand their meanings and their appropriate answers.")),
    <.p(" If the sentence has grammatical errors or is not a complete sentence, please answer ",
      " questions according to the sentence's meaning to the best of your ability. "),
    <.p("Please read through the examples if you need more details.")
  )


  val instructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "sdvalidationCollapseCookie",
        tabs = List(
          "Overview" -> sdvalidationOverview,
          "Examples" -> CommonInstructions.nonverb_span_examples,
          "Controls" -> CommonInstructions.validationControls,
          "Conditions & Payment" -> CommonInstructions.validationConditions(qasrl.crowd.QASDSettings.default)
        )
      )
    )
  )


}
