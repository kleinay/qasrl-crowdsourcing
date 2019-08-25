package qasrl.crowd

// default settings
trait QASRLSettings {

  // used as URL parameters that indicate to the client which interface to use

  val generationTaskKey = "generation"
  val validationTaskKey = "validation"
  val sdgenerationTaskKey = "sdgeneration"  // generation of non-verb targets (sem-dep)
  val sdvalidationTaskKey = "sdvalidation"
  val dashboardTaskKey = "dashboard"

  // annotation pipeline hyperparameters

  val generationRewardCents = 5
  def generationReward = generationRewardCents * 0.01

  def generationFirstQBonusCents = 4

  def generationBonus(nValidQAs: Int) = {
    // no bonus for the first question, hence -1
    val cents = (0 until (nValidQAs - 1)).map(_ + generationFirstQBonusCents).sum
    cents * 0.01
  }

  val validationReward = 0.08
  val validationBonusPerQuestion = 0.02
  val validationBonusThreshold = 4

  def validationBonus(numQuestions: Int) =
    math.max(0.0, validationBonusPerQuestion * (numQuestions - validationBonusThreshold))

  // Maximum number of generated questions allowed
  val generationMaxQuestions = 7

  val generationCoverageQuestionsPerVerbThreshold = 2.0
  val generationCoverageGracePeriod = 15

  val generationAccuracyBlockingThreshold = 0.60
  val generationAccuracyGracePeriod = 15

  val generationAgreementBlockingThreshold = 0.5
  val generationAgreementGracePeriod = 15

  val validationAgreementBlockingThreshold = 0.85
  val validationAgreementGracePeriod = 10
}

object QASRLSettings {
  val default = new QASRLSettings {}
}
