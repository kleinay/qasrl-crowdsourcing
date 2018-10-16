package qasrl.crowd

object QASDAdverbTemplates {
  val list =
    """What is/was/will [W]?
      |What happens/happened [W]?
      |What doesn’t/will/won’t/didn’t happen [W]?
      |What did(n’t) someone do [W]?
      |What [W] happens/happened?
      |What will/won’t/doesn’t/didn’t [W] happen?
      |To what degree is something/someone [W]?
      |To what degree do something happens [W]?
      |To what degree did someone do something [W]?
      |To what degree did something happen [W]?"""
      .stripMargin
      .split("\n")
      .toList
}