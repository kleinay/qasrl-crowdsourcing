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

object ValidationInstructions extends Instructions {

  import settings._
  import InstructionsComponent._


  val validationOverview = <.div(
    <.p(Styles.badRed, """Read through all of the instructions and make sure you understand the interface controls before beginning. A full understanding of the requirements will help maximize your agreement with other workers so you can retain your qualification."""),
    <.p("""This task is for an academic research project of natural language processing.
           We wish to deconstruct the meanings of verbs in English sentences into lists of questions and answers.
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


  val instructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "validationCollapseCookie",
        tabs = List(
          "Overview" -> validationOverview,
          "Controls" -> CommonInstructions.validationControls,
          "Conditions & Payment" -> CommonInstructions.validationConditions(settings),
          "Examples" -> CommonInstructions.verb_span_examples
        )
      )
    )
  )

}