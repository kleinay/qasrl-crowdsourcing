import qasrl.crowd.QASRLSettings

package object example {
  implicit val settings = new QASRLSettings {
    override val generationCoverageQuestionsPerVerbThreshold = 1.3
  }
}
