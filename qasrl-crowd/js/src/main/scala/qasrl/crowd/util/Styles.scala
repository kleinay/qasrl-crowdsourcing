package qasrl.crowd.util

import scalacss.DevDefaults._

import scala.language.postfixOps

object Styles extends StyleSheet.Inline {
  import dsl._

  val mainContent = style(
    font := "Helvetica"
  )

  val unselectable = style(
    userSelect := "none"
  )

  val answerIndicator = style(
    color(c"rgb(20, 180, 20)")
  )

  val listlessList = style(
    margin(0 px),
    padding(0 px),
    listStyleType := "none"
  )

  val specialWord = style(
    fontWeight.bold,
    textDecoration := "underline"
  )

  val goodGreen = style(
    color(c"rgb(48, 140, 20)"),
    fontWeight.bold
  )

  val verbFormPurple = style(
    color(c"rgb(153, 0, 153)"),
    fontWeight.bold
  )

  val badRed = style(
    color(c"rgb(216, 31, 00)"),
    fontWeight.bold
  )

  val redundantOrange = style(
    color(c"rgb(255, 177, 00)"),  // #ffb100 orange (as redundant color)
    fontWeight.bold
  )

  val bolded = style(fontWeight.bold)

  val underlined = style(
    textDecoration := "underline"
  )

  val niceBlue = style(
    style(fontWeight.bold),
    color(c"rgb(50, 164, 251)"))

  val targetWord = style(
    specialWord,
    niceBlue)

  val uncomfortableOrange = style(
    color(c"rgb(255, 135, 0)")
  )

  val disabledGray = style(
    color(c"rgb(128, 128, 128)")
  )

  val largeText = style(
    fontSize(20 pt)
  )

  val smallButton = style(
    Styles.unselectable,
    minHeight :=! "30px",
    marginLeft :=! "3px",
    border :=! "2px solid",
    borderRadius :=! "2px",
    textAlign :=! "center",
    width :=! "45px"
  )

  val greenButtonColor : String = "#1a9e15"
  val redButtonColor : String = "#F00000"
}
