package qasrl.crowd

object QASDAdjectiveTemplates {
  val list =
    """Who is [W]?
      |Who is not [W]?
      |What is [W]?
      |What is not [W]?
      |What kind of [W]?
      |How much [W]?
      |To what extent is something [W]?
      |To what degree is something [W]?"""
      .stripMargin
      .split("\n")
      .toList
}
