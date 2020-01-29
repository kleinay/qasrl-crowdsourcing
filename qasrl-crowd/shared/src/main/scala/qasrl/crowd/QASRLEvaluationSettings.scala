package qasrl.crowd

// default settings
trait QASRLEvaluationSettings {

  // used as URL parameters that indicate to the client which interface to use

  val evaluationTaskKey = "evaluation"

  // annotation pipeline hyperparameters

  val validationReward = 0.04
  val validationBonusPerQuestion = 0.02
  val validationBonusThreshold = 1

  val arbitrationReward = 0.8
  val arbitrationBonusPerQuestion = 0.03
  val arbitrationBonusThreshold = 2


  def arbitrationBonus(numQuestions: Int) =
    math.max(0.0, arbitrationBonusPerQuestion * (numQuestions - arbitrationBonusThreshold))

  def validationBonus(numQuestions: Int) =
    math.max(0.0, validationBonusPerQuestion * (numQuestions - validationBonusThreshold))

  val validationAgreementBlockingThreshold = 0.85
  val validationAgreementGracePeriod = 20

  val invalidProportionBlockingThreshold = 0.9
  val invalidProportionBlockingGracePeriod = 15

  val invalidProportionEstimateLowerBound = .10
  val invalidProportionEstimateUpperBound = .25
}

object QASRLEvaluationSettings {
  val default = new QASRLEvaluationSettings {}
}
