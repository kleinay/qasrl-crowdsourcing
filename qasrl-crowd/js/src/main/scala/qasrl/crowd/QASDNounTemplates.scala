package qasrl.crowd

object QASDNounTemplates {
  val list =
    """What kind of [W]?
      |Which [W]?
      |Whose [W]?
      |How much [W]?
      |How many [W]?
      |[W] for how long?
      |Where is/was (the )[W]?
      |When is/was (the )[W]?
      |What is/was the time of (the )[W]?
      |What is (the )[W] of?
      |What is (the )[W] <PREP>?
      |What is (not )[W]?
      |Who is (not )[W]?"""
      .stripMargin
      .split("\n")
      .toList
}