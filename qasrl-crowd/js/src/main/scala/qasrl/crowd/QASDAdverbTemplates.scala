package qasrl.crowd

object QASDAdverbTemplates {
  val list =
    """What <BE_SG> [W]?
      |What happens/happened [W]?
      |What was(n’t) done [W]?
      |What doesn’t/will/won’t/didn’t happen [W]?
      |What does/did(n’t) someone do [W]?
      |What [W] (not )happen/happens/happened?
      |What [W] will/won’t/doesn’t/didn’t happen?
      |To what degree is something/someone [W]?
      |To what degree did someone do something [W]?
      |To what degree does/did something happen [W]?"""
      .stripMargin
      .split("\n")
      .toList

  val listWithId : List[String] = for ((e, i) <- list.zipWithIndex) yield e+"#"+i.toString
}