package qasrl.crowd

object QASDNounTemplates {
  val list =
    """What kind of [W]?
      |Which [W]?
      |Whose [W]?
      |How much [W]?
      |How many [W]?
      |[W] for how long?
      |When is/are/was/were (the )[W]?
      |What is/was the time of (the )[W]?
      |Who/What is an example of [W]?
      |Who/What is/was (a/an/the )[W]?
      |Where is/are/was/were (the )[W]?
      |Who/What is/was (the )[W] <PREP>?
      |What is/was (the )[W] to do?
      |Where is/are/was/were (the )[W] <PREP>?
      |Who/What is/was <PREP> (a/an/the )[W]?"""
      .stripMargin
      .split("\n")
      .toList
}