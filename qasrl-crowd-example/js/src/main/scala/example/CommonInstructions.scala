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


  val qanom_examples = <.div(
    TooltipsComponent(
      <.div(
        <.p(" Below, for each prompt, we illustrate good (green) and bad (red) responses. ",
          " Hover the mouse over the underlined examples for an explanation. "),


        <.blockquote(
          ^.classSet1("blockquote"),
          "This announcement seem to incentive workers to reduce their ", <.span(Styles.targetWord, " demands"), " from the organization. "),
        <.ul(
          example_is_verbal(true),
          example_QA(
            "Who demands something?",
            "workers",
            true
          ),
          example_QA(
            "What does someone demand something from?",
            "the organization",
            true
          )
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          "This ", <.span(Styles.targetWord, " announcement"), " seem to incentive workers to reduce their demands from the organization. "),
        <.ul(
          example_is_verbal(true,
            """'announcement' is the event (or result) of someone announcing something.
              |It would make sense to ask about this target noun - 'Who announced something?' or 'What did someone announce?'.
              |Note that in this sentence, we don't have enough context and information for answering these questions, so we will
              |toggle "No Q-A Applicable"; nevertheless the target is clearly a verbal event. """.stripMargin),
          example_QA(
            "Who announced something?",
            "the organization",
            false,
            """ Even though somewhat plausible, there is no way to confirm that it was the organization's announcement.
              | You should highlight only answers that are stated explicitly by the sentence. """.stripMargin)
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          "This announcement seem to incentive workers to reduce their demands from the ", <.span(Styles.targetWord, " organization"), ". "),
        <.ul(
          example_is_verbal(false,
            """"organization" here is not describing the event of "organizing"
              |(nor a product of such an event), but rather a standalone concept
              |which is not related to carrying out the verb "organize".""".stripMargin)
        ),


        // Simple "adjective-like" nominalization
        <.blockquote(
          ^.classSet1("blockquote"),
          "Then, add the ", <.span(Styles.targetWord, " baking"), " powder and stir. "),
        <.ul(
          example_is_verbal(true,
            """"baking" in this sentence is describing the kind of "powder" one is referring to.
              |It is a powder used for baking purposes.
              |Thus, "baking" is describing the verbal event corresponding to the verb "bake".""".stripMargin)
        ),


        // More complex examples
        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption scandal on local officials, who today refused to promise that they would resume the investigation before year's ", <.span(Styles.targetWord, " end"), ". "),
        <.ul(
          example_is_verbal(true),
          example_QA(
            "What is ending?",
            "year",
            true)
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption ", <.span(Styles.targetWord, " scandal"), " on local officials, who today refused to promise that they would resume the investigation before year's end. "),
        <.ul(
          example_is_verbal(false, "The target, 'scandal', is indeed an event, but not a VERBAL event; no related verb can describe it, or to be used for asking questions about it.")
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          <.span(Styles.targetWord, "Protesters"), " blamed the corruption scandal on local officials, who today refused to promise that they would resume the investigation before year's end. "),
        <.ul(
          example_is_verbal(false, "Even though 'protesters' is indeed a noun that is related to a verb - 'protest' - it doesn't refer to the EVENT of 'protesting' (but to the people who protest). ")
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the ", <.span(Styles.targetWord, " corruption"), " scandal on local officials, who today refused to promise that they would resume the investigation before year's end. "),
        <.ul(
        example_is_verbal(true, "'corruption' can be realized as the event of people corrupting some public system. "),
        example_QA(
          "Who might have been corrupting something?",
          "local officials",
          true,
          """ This is true only according to the protesters, blaming the local officials with the corruption. Therefore, we use 'might' to keep the resulting Q&A correct. """)
        ),


        <.blockquote(
          ^.classSet1("blockquote"),
          "Protesters blamed the corruption scandal on local officials, who today refused to promise that they would resume the ", <.span(Styles.targetWord, " investigation"), " before year's end. "),
        <.ul(
          example_is_verbal(true),
          example_QA(
            "Who investigated something?",
            "local officials",
            true),
          example_QA(
            "When wouldn't someone investigate something?",
            "before year's end",
            true),
          example_QA(
            "What did someone investigate?",
            "the corruption scandal",
            false,
            "There is not enough context to verify this reading - the local official might have been investigating something else, the scandal being about their refusal to resume investigation.")
        )


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
          "His recent ", <.span(Styles.targetWord,"appearance"), " at the Metropolitan Museum felt more like a party , or a highly polished jam session with a few friends , than a classical concert . "),
        <.ul(
          example_QA(
            "Where was the appearance?",
            "Metropolitan Museum",
            true),
          example_QA(
            "When was the appearance?",
            "recent",
            true,
            """ 'Which appearance?' would also have been okay. """),
          example_QA(
            "When is an appearance?",
            "recent",
            false,
            """The question is valid, but share the same meaning with the previous question, thus marked as Redundant. """,
            true),  // redundant
          example_QA(
            "Whose appearance?",
            "His",
            true),
          example_QA(
            "What kind of appearance?",
            "jam session",
            false,
            """This Q&A is not asserted from the sentence, as it is only claimed that the appearance felt like a jam session. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly ", <.span(Styles.targetWord,"polished"), " jam session with a few friends , than a classical concert . "),
        <.ul(
          example_QA(
            "To what degree is something polished?",
            "highly",
            true),
          example_QA(
            "What is polished?",
            "session",
            true,
            """ 'jam session' is also correct. Shorter answer spans are usually preferred."""),
          example_QA(
            "What is polished?",
            "a highly polished jam session",
            false,
            """The answer shouldn’t usually include the target word. In this case, it is sufficient to highlight ‘jam session’ to refer the mention of the relevant answer. """)
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the ", <.span(Styles.targetWord,"Metropolitan Museum"), " felt more like a party , or a highly polished jam session with a few friends , than a classical conecrt . "),
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
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly polished jam session with a few friends , than a classical ", <.span(Styles.targetWord,"concert"), " . "),
        <.ul(
          example_QA(
            "What kind of concert?",
            "classical",
            true,
            """The concert the highlighted word is referring to is described as classical, which makes this question and answer correct. The fact that the referred concert is not a factual event in the context of the whole sentence is not important - the questions should refer the entity or property described by the word itself.""")
        ),

        <.blockquote(
          ^.classSet1("blockquote"),
          "His recent appearance at the Metropolitan Museum felt more like a party , or a highly polished jam ", <.span(Styles.targetWord,"session"), " with a few friends , than a classical concert . "),
        <.ul(
          example_QA(
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