package example
//import Instructions._

import spacro.ui._

import qasrl.crowd.QASRLDispatcher
import qasrl.crowd.util._

import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._

import scalajs.js.JSApp

import upickle.default._


import GenerationInstructions.{generationControls, generationConditions, verb_span_examples }

object SDGenerationInstructions extends Instructions {
  import settings._
  import InstructionsComponent._

  val instructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "sdgenerationCollapseCookie",
        tabs = List(
          "Overview" -> sdgenerationOverview,
          "Interface & Controls" -> generationControls,
          "Conditions & Bonuses" -> generationConditions,
          "Examples" -> <.div(verb_span_examples)
        )
      )
    )
  )


  val sdgenerationOverview = <.div(
    <.p(Styles.badRed, """Read through all of the instructions and make sure you understand the task and interface controls before beginning. A full understanding of the requirements will help make sure validators approve your work and you can retain your qualification."""),
    <.p("""This task is for an academic research project at natural language processing.
        We wish to deconstruct the meanings of English sentences into relations between words in the sentence.
        You will be presented with a selection of English text with a target word written in bold."""),
    <.p("""You will write questions about the target word and highlight their answers in the original sentence. """,
      <.b(""" Note: it takes exactly 2 clicks to highlight each answer; see the Interface & Controls tab for details. """),
      """ Questions are taken from a closed list of options, which is suggested by a drop-down menu list. An autocomplete functionality in the interface is provided in order to aid the search of an appropriate question. """,
      """ For example, the prompt below should elicit the following questions and answers: """),
    <.blockquote(
      ^.classSet1("blockquote"),
      "Protesters blamed the recent corruption ", <.span(Styles.bolded, "scandal"), " on local officials, who today refused to promise that they would resume the investigation before year's end. "),
    <.ul(
      <.li("What is the scandal of? --> corruption"),
      <.li("What is the time of the scandal? --> recent")
    ),
    <.h2("Guidelines"),
    <.ol(
      <.li(
        <.span(Styles.bolded, "Correctness. "),
        """Each answer must be a true answer to the question according to the sentence given. For example, """,
        <.span(Styles.bolded, "Whose corruption? --> local officials"), """ is invalid, since one cannot assert that from the sentence, but only that protesters so claim.
             Your questions will be judged by other annotators, and you must retain an accuracy of
             ${(100.0 * generationAccuracyBlockingThreshold).toInt}% in order to remain qualified. """),
      <.li(
        <.span(Styles.bolded, "Exhaustiveness. "),
        s"""You must write as many questions, and as many answers to each question, as possible.
             You must retain an average of 0.5 question per target in order to remain qualified for the HIT. Notice however that some prompts have no appropriate question to ask about. If this is the case, you can just submit an empty form. You will be awarded a bonus for each new question,
             starting at ${generationRewardCents}c and going up by 1c for each additional question.
             However, note that none of the answers to your questions may overlap.
             If there is more than one possible question that has the same answer, just write one of them."""
      )
    ),
    <.p("If you are not sure about certain cases, please check the examples.")
  )


}
