package qasrl.crowd

import qasrl.crowd.util.implicits._

import cats.implicits._

// judgment (per target) of agreement with other generators on (at least one) answer span
case class WSSDGenAgreementJudgment(
                                 hitId: String,
                                 Question: String,
                                 isValid: Boolean
                               )

case class QAWSSDGenerationWorkerStats(
                                       workerId: String,
                                       numAssignmentsCompleted: Int,
                                       wssdgenAgreementJudgments: Vector[WSSDGenAgreementJudgment]) {

  def numQAPairsWritten: Int = wssdgenAgreementJudgments.size
  def numQAPairsValid: Int = wssdgenAgreementJudgments.count(_.isValid)

  def genAgreementAccuracy = numQAPairsValid/numQAPairsWritten.toDouble

  def addGenAgreementJudgments(
                                judgments: Vector[GenAgreementJudgment]
                              ) = this.copy(
    genAgreementJudgments = judgments ++ this.genAgreementJudgments
  )


}
object QAWSSDGenerationWorkerStats {
  def empty(workerId: String) = QAWSSDGenerationWorkerStats(workerId, 0, Vector.empty[GenAgreementJudgment])
}
