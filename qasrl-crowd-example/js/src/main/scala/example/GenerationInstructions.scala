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

object GenerationInstructions extends Instructions {
  import settings._
  import InstructionsComponent._


  val generationOverview = <.div(
    <.p(Styles.badRed,
      """Read through all of the instructions and make sure you understand the interface controls before beginning.
        |A full understanding of the requirements will help make sure
        |we approve your work and you can retain your qualification.""".stripMargin),
    <.p("""This task is for an academic research project of natural language processing.
          We wish to extract the meaning of special "verbal" nouns using questions and answers.
          You will be presented with an English sentence with a highlighted """,
      <.span(Styles.targetWord, """target noun"""), "."),
    <.p("""Your task is twofold. """,
      <.br(),
      " (1) ",
      <.span(Styles.bolded, """ Is Verbal: """),
      """ Determine whether, in context, the """,
        <.span(Styles.targetWord, """target noun"""),
      """ corresponds to some verb-related """,
      spanWithTooltip("event",
        "We use the term “event” in the widest meaning possible - everything that can be denoted by a verb."),
      """ (Yes / No). """,
      <.br(),
      " (2) ",
      <.span(Styles.bolded,  """ Q-A Generation: """),
      <.span(""" Write questions about the """, <.span(Styles.targetWord, """target noun"""),
        """ using the provided """),
      <.span(Styles.verbFormPurple, "corresponding verb"),
      <.span(""", and mark their answers by highlighting text in the sentence.""")
    ),

    // Example
    <.p(""" For example, the prompt below should elicit the following response: """),
    <.blockquote(
      ^.classSet1("blockquote"),
      "The FTC decided that it would not challenge Google's last year ",
      <.span(Styles.targetWord, Styles.underlined, "acquisition"),
      " of Waze. "
    ),
    <.span(
      <.i("Does the highlighted noun refer to a verbal event? "),
      <.span(Styles.goodGreen, "Yes"),
      <.br()
    ),
    <.i("Q-A Generation (using the verb ", <.span(Styles.verbFormPurple, "acquire"), "):"),
    <.ul(
      <.li("What acquired something? --> Google"),
      <.li("What was acquired? --> Waze"),
      <.li("When did something acquired something? --> last year")
    ),

    // Specifications
    <.h2("Guidelines - Is Verbal"),
    <.ul(
      <.li(
        """The best criteria for defining whether a target noun corresponds
          |to a verbal event is using the """.stripMargin,
        <.i("""Q-A test"""),
        """: such noun instance is denoting
          |an action, process, experience, or an outcome of these, in a way that allows one
          |to ask questions about it using a related verb.""".stripMargin,
        <.br(),
        <.span(
          """In the example above, the """,
          <.span(Styles.targetWord, "acquisition"),
          """ can be described as an event of the verb `to """, <.span(Styles.verbFormPurple, "acquire"),
          """`, thus proper for asking questions
            |like "what acquired something??" or "what was acquired?".""".stripMargin)
      ),
      <.li(
        """If the """, <.span(Styles.targetWord, "noun"),
          """ isn’t “verbal”, toggle “No” and submit.
          |If it is, in most cases you can ask verbal questions about it,
          |for which the answers are fragments of the sentence. """.stripMargin
      ),
      <.li(
        """In case the """, <.span(Styles.targetWord, "noun"),
          """ is “verbal” but the provided """, <.span(Styles.verbFormPurple, "verb"),
          """ is wrong, please comment about it on the Feedback box. """.stripMargin
      )
    ),

      // Q-A Generation Guidelines
    <.h2("Guidelines - Q-A Generation"),
      // technical details and interface specifications
    <.h4("Interface"),
    <.ul(
      <.li(
        """Questions are required to follow a strict format, which is enforced by
          |autocomplete functionality in the interface
          |(more details in the Question Format tab).""".stripMargin),
      <.li(
        """Answers are selected by highlighting spans in the sentence.
          |It takes exactly 2 clicks to highlight each answer;
          |click on the first word in the span, then on the last one (so that the answer turns """.stripMargin,
        <.span(^.backgroundColor := "#FFFF00", "yellow"), ")."),
      <.li(
        """If the target is a verbal event,
          |but no question regarding it is answerable by the sentence, toggle """.stripMargin,
        <.i("No Q-A Applicable."))
    ),

        // content guidelines
    <.h4("Guiding principles"),
    <.ol(
      <.li(
        <.span(
          <.span(Styles.bolded, "Correctness. "),
          <.span("""Each question-answer pair must satisfy the litmus test that if you substitute
            the answer back into the question, the result is a """),
          <.i("""grammatical """),
          <.span("""statement, and it is """),
          <.i("""certainly true """),
          <.span("""according to the sentence given. For example, """),
          <.span(Styles.bolded, "What acquired something? --> Google"), """ becomes """,
          <.span(Styles.goodGreen, "Google acquired something, "), """ which is valid, while """,
          <.span(Styles.bolded, "What acquired? --> Google"), """ would become """,
          <.span(Styles.badRed, "Google acquired, "),
          <.span(s""" which is ungrammatical, so it is invalid.
                Your questions will be judged by other annotators, and you must retain
                a reasonable agreement rate
                with others in order to remain qualified. """)
          // s"""an accuracy of ${(100.0 * generationAccuracyBlockingThreshold).toInt}% """
        )
      ),
      <.li(
        <.span(Styles.bolded, "Event-relevance. "),
        s"""The answer to a question must pertain to the participants, time, place, reason, etc., of """,
        <.span(Styles.bolded, " the target event in the sentence. "),
        """ For example, if the sentence is """,
        <.span(Styles.bolded,
          " He made a ",
          <.span(Styles.targetWord, Styles.underlined, "promise"),
          " to come tomorrow, "),
        """ you may """, <.span(Styles.bolded, " not "), " write ",
        <.span(Styles.badRed, " When did someone promise to do something? --> tomorrow, "),
        """ because tomorrow is """, <.i(" not "),
        " the time that he made the promise, but rather the time that he might come."
      ),
      <.li(
        <.span(Styles.bolded, "Exhaustiveness. "),
        s"""You should write as many questions, and as many answers to each question, as possible.
           You will be awarded a bonus for each new question,
           starting at ${generationRewardCents}c and going up by 1c for each additional question.
           However, note that none of the answers to your questions may overlap. """
          //"""If there is more than one possible question that has the same answer, just write one of them."""
      )
    ),
    // additional comments
//    <.p("Please check out the Examples tab for a better understanding of the task."),
    <.p("")
  )


  val generationControls = <.div(
    <.ul(
      <.li(
        <.span(Styles.bolded, "Questions & Autocomplete. "),
        """You can write questions by typing or selecting items in the autocomplete menu.
        You may navigate the menu using the mouse or the up & down arrows and enter key.
        It may be easiest to start off with the mouse and then switch to typing when you get used to the question format.
        You can use tab and shift+tab to switch between questions."""),
      <.li(
        <.span(Styles.bolded, "Auto-suggest. "),
        """Once you have written at least one question,
        the autocomplete dropdown will start proposing complete questions.
        The suggestions are based on the structure of your previous questions, so to get the most out of them,
        write questions with more structure (e.g., "Who looked at someone?") rather than
        less (e.g., "Who looked?").
        """),
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


  val generationConditions = <.div(
    <.p(s"""Each question-answer pair after the first will earn you a bonus:
          ${dollarsToCents(generationReward)}c for the second question, ${dollarsToCents(generationReward) + 1}c for the third,
          then ${dollarsToCents(generationReward) + 2}c, etc.
          While at least one is required to submit the HIT,
          you will need to write more than ${generationCoverageQuestionsPerVerbThreshold} questions on average in order to stay qualified.
          On average, it should take less than 30 seconds per question-answer pair, and be much quicker with practice.
          """),
    <.p("""Your questions will be evaluated by other annotators, and """,
      <.b(""" you will only be awarded bonuses for your valid question-answer pairs. """),
      s""" (However, your questions-per-target average will include invalid questions.)
          The bonus will be awarded as soon as validators have checked all of your question-answer pairs,
          which will happen shortly after you submit (but will vary depending on worker availability).
          Your accuracy will be updated as your questions are validated
          and shown to you just below the task interface.
          (Note that the validators will sometimes make mistakes,
          so there is an element of randomness to it: don't read too deeply into small changes in your accuracy.)
          If this number drops below ${(100 * generationAccuracyBlockingThreshold).toInt},
          you will be disqualified from this task. """)
  )


  val generationQuestionFormat = <.div(
    <.p(""" This section is just for reference to help you understand the format of the questions.
        They all will be formed by filling slots like in the table below.
        The set of words you may use in each slot may depend on the words you wrote in the previous slots."""),
    <.table(
      ^.classSet1("table"),
      <.thead(
        <.tr(
          <.th("Wh-word"), <.th("Auxiliary"), <.th("Subject"), <.th("Verb"), <.th("Object"), <.th("Preposition"), <.th("Misc")
        )
      ),
      <.tbody(
        <.tr(<.td("Who"), <.td(), <.td(), <.td("blamed"), <.td("someone"), <.td(), <.td()),
        <.tr(<.td("What"), <.td("did"), <.td("someone"), <.td("blame"), <.td("something"), <.td("on"), <.td()),
        <.tr(<.td("Who"), <.td(), <.td(), <.td("refused"), <.td(), <.td("to"), <.td("do something")),
        <.tr(<.td("When"), <.td("did"), <.td("someone"), <.td("refuse"), <.td(), <.td("to"), <.td("do something")),
        <.tr(<.td("Who"), <.td("might"), <.td(), <.td("resume"), <.td("something"), <.td(), <.td())
      )
    )
  )


  val instructions = <.div(
    Instructions(
      InstructionsProps(
        instructionsId = "instructions",
        collapseCookieId = "generationCollapseCookie",
        tabs = List(
          "Overview" -> generationOverview,
          "Examples" -> <.div(CommonInstructions.qanom_examples),
          "Interface & Controls" -> generationControls,
          "Question Format" -> generationQuestionFormat,
          "Conditions & Bonuses" -> generationConditions
        )
      )
    )
  )

}