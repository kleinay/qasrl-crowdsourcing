package qasrl.crowd

object QASDNounTemplates {
  val list =
    """What kind of [W]?
      |Which [W]?
      |Whose [W]?
      |How much [W]?
      |How many [W]?
      |[W] for how long?
      |Where is [W]?
      |What was the time of (the )[W]?
      |What is (not )[W]?
      |What is (the )[W] of?
      |What is (the )[W] <PREP>?
      |Who is (not )[W]?"""
      .stripMargin
      .split("\n")
      .toList
}