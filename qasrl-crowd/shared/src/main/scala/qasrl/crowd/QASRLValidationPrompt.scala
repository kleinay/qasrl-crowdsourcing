package qasrl.crowd

case class QASRLValidationPrompt[SID](
  genPrompt: QASRLGenerationPrompt[SID],
  sourceHITTypeId: String,
  sourceHITId: String,
  sourceAssignmentId: List[String], // changed to contain all (aggregated) genAssignments of genHIT, todo change name
  qaPairs: List[VerbQA]
) {
  def id = genPrompt.id
}
