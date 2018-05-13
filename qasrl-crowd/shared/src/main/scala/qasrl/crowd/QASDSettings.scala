package qasrl.crowd

trait QASDSettings extends QASRLSettings {

  override val generationRewardCents = 3

  val generationFirstQBonusCents = 5

  override def generationBonus(nValidQAs: Int) = {
    val cents = (0 until nValidQAs).map(_ + generationFirstQBonusCents).sum
    cents * 0.01
  }

  override val generationCoverageQuestionsPerVerbThreshold = 0.25

  // validation conditions
  override val validationReward = 0.05
  override val validationBonusPerQuestion = 0.02
  override val validationBonusThreshold = 2

}

object QASDSettings {
  val default = new QASDSettings {}
}