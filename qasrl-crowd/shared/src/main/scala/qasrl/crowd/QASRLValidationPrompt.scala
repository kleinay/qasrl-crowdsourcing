package qasrl.crowd

case class QASRLValidationPrompt[SID](
   genPrompt: QASRLGenerationPrompt[SID],
   sourceHITTypeId: String,
   sourceHITId: String,
   sourceAssignmentId: List[String], // changed to contain all (aggregated) genAssignments of genHIT, todo change name
   genResponses: List[QANomResponse] // contain all info about generators responses
) {
  def qaPairs : List[VerbQA] = genResponses.flatMap(_.qas)
  def id : SID = genPrompt.id
  def questions = qaPairs.map(_.question)
}
