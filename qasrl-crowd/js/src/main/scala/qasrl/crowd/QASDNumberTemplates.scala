package qasrl.crowd

object QASDNumberTemplates {
  val list =
    """What is/are there [W] of?
      |How many times [W]?
      |Out of how many is/are there [W] of something?
      |In what level of accuracy is something of quantity [W]?"""
      .stripMargin
      .split("\n")
      .toList

  val listWithId : List[String] = for ((e, i) <- list.zipWithIndex) yield e+"#"+i.toString
}