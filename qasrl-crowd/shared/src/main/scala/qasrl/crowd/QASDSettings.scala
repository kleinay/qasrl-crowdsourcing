package qasrl.crowd

trait QASDSettings extends QASRLSettings {

  override val generationRewardCents = 5

  override def generationFirstQBonusCents = 2

  override def generationBonus(nValidQAs: Int) = {
    // Change to - bonus only after the first
    //val cents = (0 until nValidQAs).map(_ + generationFirstQBonusCents).sum
    val cents = (nValidQAs-1) * generationFirstQBonusCents  // don't increase bonus
    cents * 0.01
  }
  // Maximum number of generated questions allowed
  override val generationMaxQuestions = 3

  override val generationCoverageQuestionsPerVerbThreshold = 0.8

  // validation conditions
  override val validationReward = 0.05
  override val validationBonusPerQuestion = 0.02
  override val validationBonusThreshold = 2

}

object QASDSettings {
  val default = new QASDSettings {}
}