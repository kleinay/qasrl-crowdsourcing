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

object Dispatcher extends QASRLDispatcher[SentenceId] with JSApp {

  override val generationInstructions = GenerationInstructions.instructions

  override val sdgenerationInstructions = SDGenerationInstructions.instructions

  override val validationInstructions = ValidationInstructions.instructions
}
