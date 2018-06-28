package qasrl.crowd

object QASDAdjectiveTemplates {
  val list =
    """Who is (not )[W]?
      |What is (not )[W]?
      |What kind of [W]?
      |What is someone/something [W] <PREP>?
      |Where is someone/something [W]( <PREP>)?
      |How much [W]?
      |To what extent/degree is something [W]?
      |In what sense is something [W]?
      |[W] to what extent?"""
      .stripMargin
      .split("\n")
      .toList
}
