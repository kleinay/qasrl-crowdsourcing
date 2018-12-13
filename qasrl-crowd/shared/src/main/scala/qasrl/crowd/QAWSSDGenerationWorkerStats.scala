package qasrl.crowd

import qasrl.crowd.util.implicits._

import cats.implicits._

// judgment (per QA pair) of agreement with other generators (on a word<->word relation)
case class WSSDGenAgreementJudgment(
                                 hitId: String,
                                 targetIndex: Int,
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
                                judgments: Vector[WSSDGenAgreementJudgment]
                              ) = this.copy(
    genAgreementJudgments = judgments ++ this.genAgreementJudgments
  )


}
object QAWSSDGenerationWorkerStats {
  def empty(workerId: String) = QAWSSDGenerationWorkerStats(workerId, 0, Vector.empty[WSSDGenAgreementJudgment])
}
