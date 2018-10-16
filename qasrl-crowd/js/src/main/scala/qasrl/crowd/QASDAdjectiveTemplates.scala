package qasrl.crowd

object QASDAdjectiveTemplates {
  val list =
    """What/Who is (not )[W]?
      |What/Who is (not )[W] <PREP> something?
      |What kind of [W]?
      |What/Who is someone/something [W] <PREP>?
      |In what sense is something [W]?
      |How much [W]?
      |To what extent/degree is something [W]?
      |[W] to what extent?"""
      .stripMargin
      .split("\n")
      .toList
}
