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


object SDGenerationInstructions extends Instructions {
  import qasrl.crowd.QASDSettings.default._
  import InstructionsComponent._


  val sdgenerationOverview = <.div(
    <.p(Styles.badRed, """Read through all of the instructions and make sure you understand the task and interface controls before beginning. A full understanding of the requirements will help make sure validators approve your work and you can retain your qualification."""),
    <.p("""This task is for an academic research project of natural language processing.
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
        <.span(Styles.bolded, "Whose corruption? --> local officials"), s""" is invalid, since one cannot assert that from the sentence, but only that protesters so claim.
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


  val nonverb_span_examples = <.div(
    TooltipsComponent(
      <.div(
        <.p("Below, for each target word, we list a complete set of good questions (green) and some bad ones (red). ",
          " Hover the mouse over the underlined examples for an explanation. "),
        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent ", <.span(Styles.bolded,"appearance"), " at the Metropolitan Museum felt more like a party , or a highly polished jam session with a few friends , than a classical concert . "),
        <.ul(
          example(
            "Where was the appearance?",
            "Metropolitan Museum",
            true),
          example(
            "When was the appearance?",
            "recent",
            true,
            """ 'Which appearance?' would also have been okay. """),
          example(
            "Whose appearance?",
            "His",
            true),
          example(
            "What is the appearance of?",
            "jam session",
            false,
            """This Q &A is not asserted from the sentence, as it is only claimed that the apperance felt like a jam session. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly ", <.span(Styles.bolded,"polished"), " jam session with a few friends , than a classical concert . "),
        <.ul(
          example(
            "To what degree is something polished?",
            "highly",
            true),
          example(
            "What is polished?",
            "jam session",
            true),
          example(
            "What is polished?",
            "a highly polished jam session",
            false,
            """The answer shouldn’t usually include the target word. I this case, it is sufficient to highlight ‘jam session’ to refer the mention of the relevant answer. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly polished jam session with a few friends , than a classical ", <.span(Styles.bolded,"concert"), " . "),
        <.ul(
          example(
            "What kind of concert?",
            "classical",
            true,
            """The concert the highlighted word is referring to is described as classical, which makes this question and answer correct. The fact that the referred concert is not a factual event in the context of the whole sentence is not important - the questions should refer the entity or property described by the word itself.""")
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly polished jam ", <.span(Styles.bolded,"session"), " with a few friends , than a classical concert . "),
        <.ul(
          example(
            "What kind of session?",
            "jam / highly polished",
            true,
            """When answering, list all the phrases in the sentence that answer the question correctly. Notice that independent modifiers of the same noun should be regarded as multiple answers. """)
        )

      )
    )
  )


  val sdgenerationControls = <.div(
    <.ul(
      <.li(
        <.span(Styles.bolded, "Questions & Autocomplete. "),
        """You can write questions by typing or selecting items in the autocomplete menu.
        You may navigate the menu using the mouse or the up & down arrows, and select a question using the enter key.
        It may be best to start off using typing, until you get familiar with the optional question templates.
        You can use tab and shift+tab to switch between questions."""),
      <.li(
        <.span(Styles.bolded, "Answers. "),
        "To highlight an answer, first click on the first word in the answer, which will turn ",
        <.span(^.backgroundColor := "#FF8000", "orange"),
        ". Then click on the last word in the answer (which may be the same word) and the whole phrase will turn ",
        <.span(^.backgroundColor := "#FFFF00", "yellow"),
        ". (You may also click them in the opposite order.) You can highlight multiple answers to the same question in this way. ",
        " To delete an answer, click on a word in that answer while it is highlighted yellow. ",
        """ None of your answers may overlap with each other; answers to questions other than the currently selected one
        will be highlighted in """,
        <.span(^.backgroundColor := "#DDDDDD", "grey"), ".")),
    <.p("""
      When a question-answer pair is complete (the question is finished and it has at least one answer),
      its input field will turn """,
      <.span(^.backgroundColor := "rgba(0, 255, 0, 0.3)", "green"),
      """. If it violates the required formatting, it will turn """, <.span(
        ^.backgroundColor := "rgba(255, 0, 0, 0.3)", "red"
      ), """. If it is a repeat of a previous question, it will turn """, <.span(
        ^.backgroundColor := "rgba(255, 255, 0, 0.3)", "yellow"
      ), """. Only complete (green) question-answer pairs will count towards your requirements and bonus. """
    )
  )


  val sdgenerationConditions = <.div(
    <.p(s"""Each question-answer pair will earn you a bonus:
          ${dollarsToCents(generationReward)}c for the first question, ${dollarsToCents(generationReward) + 1}c for the second,
          then ${dollarsToCents(generationReward) + 2}c, etc.
          Notice that some target words don't have a suitable question to ask about.
          In that case, you are allowed to submit the HIT without any generated questions.
          However, you will need to write more than ${generationCoverageQuestionsPerVerbThreshold} questions on average in order to stay qualified.
          On average, it should take less than 30 seconds per question-answer pair, and be much quicker with practice.
          """),
    <.p("""Your questions will be evaluated by other annotators, and """,
      <.b(""" you will only be awarded bonuses for your valid question-answer pairs. """),
      s""" (However, your questions-per-verb average will include invalid questions.)
          The bonus will be awarded as soon as validators have checked all of your question-answer pairs,
          which will happen shortly after you submit (but will vary depending on worker availability).
          Your accuracy will be updated as your questions are validated
          and shown to you just below the task interface.
          (Note that the validators will sometimes make mistakes,
          so there is an element of randomness to it: don't read too deeply into small changes in your accuracy.)
          If this number drops below ${(100 * generationAccuracyBlockingThreshold).toInt},
          you will be disqualified from this task. """)
  )


  val instructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "sdgenerationCollapseCookie",
        tabs = List(
          "Overview" -> sdgenerationOverview,
          "Interface & Controls" -> sdgenerationControls,
          "Conditions & Bonuses" -> sdgenerationConditions,
          "Examples" -> <.div(nonverb_span_examples)
        )
      )
    )
  )

}
