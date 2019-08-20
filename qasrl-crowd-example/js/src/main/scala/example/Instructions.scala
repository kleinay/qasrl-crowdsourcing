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


trait Instructions {

  import settings._
  import InstructionsComponent._

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

  def example_is_verbal(isGood: Boolean, tooltip: String = "") =
    <.li(
      <.span(Styles.bolded, "Is Verbal: "),
      <.span(
        if(isGood) Styles.goodGreen
        else Styles.badRed,
        TagMod(
          Styles.underlined,
          dataToggle := "tooltip",
          dataPlacement := "bottom",
          ^.title := tooltip).when(tooltip.nonEmpty),
        <.span("Yes")
      )
    )


  def example_QA(question: String, answer: String, isGood: Boolean, tooltip: String = "", isRedundant: Boolean=false) =
    <.li(
      <.span(
        if(isGood) Styles.goodGreen else
        if(isRedundant) Styles.redundantOrange
        else Styles.badRed,
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

  def spanWithTooltip(spanText : String, tooltipText : String) =
    <.span(
      TagMod(
        Styles.underlined,
        dataToggle := "tooltip",
        dataPlacement := "top",
        ^.title := tooltipText).when(tooltipText.nonEmpty),
      spanText
    )



}

