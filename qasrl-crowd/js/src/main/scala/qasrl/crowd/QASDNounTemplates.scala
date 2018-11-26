package qasrl.crowd

object QASDNounTemplates {
  val list =
    """What kind of [W]?
      |Which [W]?
      |Whose [W]?
      |How much [W]?
      |How many [W]?
      |What <BE> there [W] of?    // replacing: [W] of what?
      |How long <BE> (the )[W]?   // replacing [W] for how long?
      |When <BE> (the )[W]?
      |Who/What is an example of [W]?
      |Who/What <BE_SG> (a/an/the )[W]?
      |What <BE> there (a/an/the )[W] <PREP>?
      |Where <BE> (the )[W]?
      |Who/What <BE_SG> (the )[W] <PREP>?
      |Where <BE> (the )[W] <PREP>?
      |Who/What <BE_SG> <PREP> (a/an/the )[W]?"""
      .stripMargin
      .split("\n")
      .toList

  val listWithId : List[String] = for ((e, i) <- list.zipWithIndex) yield e+"#"+i.toString
}