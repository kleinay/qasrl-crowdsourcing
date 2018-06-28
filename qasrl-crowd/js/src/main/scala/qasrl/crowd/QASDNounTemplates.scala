package qasrl.crowd

object QASDNounTemplates {
  val list =
    """What kind of [W]?
      |Which [W]?
      |Whose [W]?
      |How much [W]?
      |How many [W]?
      |[W] for how long?
      |Who/What is/was a/an [W]?
      |Who/What is/was [W]?
      |Who/What is an example of [W]?
      |Where is/was (the )[W]?
      |When is/was (the )[W]?
      |What is/was the time of (the )[W]?
      |What is/was (the )[W] of?
      |What is/was (the )[W]?
      |What is/was (the )[W] <PREP>?
      |What is/was (the )[W] to do?
      |Where is/was (the )[W] <PREP>?"""
      .stripMargin
      .split("\n")
      .toList
}