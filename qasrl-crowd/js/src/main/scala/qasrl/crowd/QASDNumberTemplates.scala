package qasrl.crowd

object QASDNumberTemplates {
  val list =
    """[W] of what?
      |Who/What is of quantity [W]?
      |What is [W] of something?
      |What is something [W] of?
      |In what level of accuracy is something of quantity [W]?"""
      .stripMargin
      .split("\n")
      .toList

  val listWithId : List[String] = for ((e, i) <- list.zipWithIndex) yield e+"#"+i.toString
}