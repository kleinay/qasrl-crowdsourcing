package qasrl.crowd

object QASDAdjectiveTemplates {
  val list =
    """What/Who <BE> (not )[W]?
      |What/Who <BE> (not )[W] <PREP> something?
      |What/Who <BE> someone/something [W] <PREP>?
      |To what extent/degree is something [W]?
      |In what sense is something [W]?"""
      .stripMargin
      .split("\n")
      .toList

  val listWithId : List[String] = for ((e, i) <- list.zipWithIndex) yield e+"#"+i.toString
}
