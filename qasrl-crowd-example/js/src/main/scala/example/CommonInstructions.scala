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
import qasrl.crowd.QASRLSettings

object CommonInstructions extends Instructions {

  import InstructionsComponent._


  val verb_span_examples = <.div(
    TooltipsComponent(
      <.div(
        <.p(Styles.bolded," This section is exactly the same between the question writing and question answering tasks. "),
        <.p(" Below, for each verb, we list a complete set of good questions (green) and some bad ones (red). ",
          " Hover the mouse over the underlined examples for an explanation. "),
        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters ", <.span(Styles.bolded, " blamed "), " the corruption scandal on local officials, who today refused to promise that they would resume the investigation before year's end. "),
        <.ul(
          example(
            "Who blamed someone?",
            "Protesters",
            true),
          example(
            "Who did someone blame something on?",
            "local officials",
            true),
          example(
            "What did someone blame someone for?",
            "the corruption scandal",
            true,
            """ "What did someone blame on someone?" would also have been okay. """),
          example(
            "Who blamed?",
            "Protesters",
            false,
            """ This question is invalid by the litmus test, because the sentence "Protesters blamed." is ungrammatical. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption scandal on local officials, who today ", <.span(Styles.bolded, " refused "), " to promise that they would resume the investigation before year's end. "),
        <.ul(
          example(
            "Who refused to do something?",
            "local officials / they",
            true,
            """When answering, list all of the phrases in the sentence that refer to the correct answer, including pronouns like "they"."""),
          example(
            "What did someone refuse to do?",
            "promise that they would resume the investigation before year's end",
            true),
          example(
            "What did someone refuse to do?",
            "promise that they would resume the investigation",
            false,
            """The answer is not specific enough: it should include "before year's end" because that was part of what they were refusing to promise."""),
          example(
            "What did someone refuse to do?",
            "resume the investigation before year's end",
            false,
            """This answer is also bad: you should instead choose the more literal answer above."""),
          example(
            "When did someone refuse to do something?",
            "today",
            true),
          example(
            "Who didn't refuse to do something?",
            "Protesters",
            false,
            """The sentence does not say anything about protesters refusing or not refusing, so this question is invalid.""")
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption scandal on local officials, who today refused to ", <.span(Styles.bolded, " promise "), " that they would resume the investigation before year's end. "),
        <.ul(
          example(
            "Who didn't promise something?",
            "local officials / they",
            true,
            "Negated questions work when the sentence is indicating that the event or state expressed by the verb did not happen."),
          example(
            "What didn't someone promise?",
            "that they would resume the investigation before year's end",
            true),
          example(
            "Who should have promised something?",
            "local officials / they",
            false,
            """The question matches the same answers as a previous question ("Who didn't promise something?"), thus toggled Redundant.""",
            true),  // redundant
          example(
            "When didn't someone promise to do something?",
            "before year's end",
            false,
            """ This question is bad because "before year's end" refers to the timeframe of resuming the investigation, not the timeframe of the promise being made.
            All such questions must pertain to the time/place of the chosen verb. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption scandal on local officials, who today refused to promise that they would ", <.span(Styles.bolded, " resume "), " the investigation before year's end. "),
        <.ul(
          example(
            "Who might resume something?",
            "local officials / they",
            true,
            """Words like "might" or "would" are appropriate when the sentence doesn't clearly indicate whether something actually happened."""),
          example(
            "What might someone resume?",
            "the investigation",
            true),
          example(
            "When might someone resume something?",
            "before year's end",
            true)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          <.span(Styles.bolded, " Let"), "'s go up to the counter and ask."),
        <.ul(
          example(
            "Who should someone let do something?",
            "'s",
            true,
            """Here, you should read 's as the word it stands for: "us".
            So by substituting back into the question, we get "someone should let us do something",
            which is what someone is suggesting when they say "Let's go". """),
          example(
            "What should someone let someone do?",
            "go up to the counter and ask",
            true,
            """It would also be acceptable to mark "go up to the counter" and "ask" as two different answers. """),
          example(
            "Where should someone let someone do something?",
            "the counter",
            false,
            """Questions should only concern the targeted verb: "letting" is not happening at the counter.""")
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "Let's ", <.span(Styles.bolded, " go "), " up to the counter and ask."),
        <.ul(
          example(
            "Who should go somewhere?",
            "'s",
            true),
          example(
            "Where should someone go?",
            "up to the counter",
            true,
            """Since both "up" and "to the counter" describe where they will go, they should both be included in the answer to a "where" question. """))
      )
    )
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
            "When is an appearance?",
            "recent",
            false,
            """The question is valid, but share the same meaning with the previous question, thus marked as Redundant. """,
            true),  // redundant
          example(
            "Whose appearance?",
            "His",
            true),
          example(
            "What kind of appearance?",
            "jam session",
            false,
            """This Q&A is not asserted from the sentence, as it is only claimed that the appearance felt like a jam session. """)
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
            "session",
            true,
            """ 'jam session' is also correct. Shorter answer spans are usually preferred."""),
          example(
            "What is polished?",
            "a highly polished jam session",
            false,
            """The answer shouldn’t usually include the target word. In this case, it is sufficient to highlight ‘jam session’ to refer the mention of the relevant answer. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the ", <.span(Styles.bolded,"Metropolitan Museum"), " felt more like a party , or a highly polished jam session with a few friends , than a classical conecrt . "),
        <.span(
          Styles.badRed,
          TagMod(
            Styles.underlined,
            dataToggle := "tooltip",
            dataPlacement := "top",
            ^.title := "Notice how no other word is shedding further information about the entity referred by the target."),
          <.span("There are no appropriate questions to ask about this target word.")
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


  def validationConditions(settings : QASRLSettings) = <.div(
    <.p(s"""You will be paid a bonus of ${dollarsToCents(settings.validationBonusPerQuestion)}c
        for every question beyond ${settings.validationBonusThreshold}, which will be paid when the assignment is approved.
        Your judgments will be cross-checked with other workers,
        and your agreement rate will be shown to you in the interface.
        If this number drops below ${(100 * settings.validationAgreementBlockingThreshold).toInt}
        you will no longer qualify for the task.
        (Note that other validators will sometimes make mistakes,
        so there is an element of randomness to it: don't read too deeply into small changes in your agreement rate.)
        Your work will be approved and the bonus will be paid within an hour.""")
  )



  val validationControls = <.div(
    <.ul(
      <.li(
        <.span(Styles.bolded, "Navigation. "),
        "Change questions using the mouse, the up and down arrow keys, or W and S."),
      <.li(
        <.span(Styles.bolded, "Invalid Questions. "),
        "Click the button labeled \"Invalid\" or press the space bar to toggle a question as invalid."),
      <.li(
        <.span(Styles.bolded, "Redundant Questions. "),
        "Click the button labeled \"Redundant\" or press R to toggle a question as redundant with respect to previous questions."),
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
        <.span(^.backgroundColor := "#DDDDDD", "grey"), "."))
  )


}