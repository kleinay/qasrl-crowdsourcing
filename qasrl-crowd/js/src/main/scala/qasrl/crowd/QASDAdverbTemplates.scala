package qasrl.crowd

object QASDAdverbTemplates {
  val list =
    """What happened [W]?
      |What did someone do [W]?
      |To what degree is something [W]?"""
      .stripMargin
      .split("\n")
      .toList
}