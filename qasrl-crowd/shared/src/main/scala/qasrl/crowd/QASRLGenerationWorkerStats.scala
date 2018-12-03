package qasrl.crowd

import qasrl.crowd.util.implicits._

import cats.implicits._

case class AccuracyJudgment(
  validatorId: String,
  isValid: Boolean
)

// judgment (per target) of agreement with other generators on (at least one) answer span
case class GenAgreementJudgment(
  hitId: String,
  Question: String,
  isValid: Boolean
)

case class QASRLGenerationWorkerStats(
  workerId: String,
  numValidatorJudgments: Int,
  numAssignmentsCompleted: Int,
  accuracyJudgments: Vector[AccuracyJudgment],
  genAgreementJudgments: Vector[GenAgreementJudgment],
  numBonusValids: Int,
  earnings: Double) {

  def numQAPairsWritten: Int = accuracyJudgments.size
  def numQAPairsValid: Int = accuracyJudgments.filter(_.isValid).size

  def accuracy = (Vector.fill(numBonusValids)(true) ++ accuracyJudgments.map(_.isValid)).proportion(identity)

  def genAgreementAccuracy = genAgreementJudgments.count(_==true)/genAgreementJudgments.size.toFloat

  def addBonusValids(n: Int) = this.copy(
    numBonusValids = this.numBonusValids + n
  )

  def removeJudgmentsByWorker(badWorkerId: String) = this.copy(
    accuracyJudgments = this.accuracyJudgments.filter(_.validatorId != badWorkerId)
  )

  def addAccuracyJudgments(
    judgments: Vector[AccuracyJudgment]
  ) = this.copy(
    numValidatorJudgments = this.numValidatorJudgments + 1,
    accuracyJudgments = judgments ++ this.accuracyJudgments
  )

  def addGenAgreementJudgments(
    judgments: Vector[GenAgreementJudgment]
  ) = this.copy(
    genAgreementJudgments = judgments ++ this.genAgreementJudgments
  )

  def registerValidationFinished(
    totalReward: Double
  ) = this.copy(
    numAssignmentsCompleted = this.numAssignmentsCompleted + 1,
    earnings = this.earnings + totalReward
  )
}
object QASRLGenerationWorkerStats {
  def empty(workerId: String) = QASRLGenerationWorkerStats(workerId, 0, 0, Vector.empty[AccuracyJudgment], Vector.empty[GenAgreementJudgment], 0, 0.0)
}
