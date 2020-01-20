package qasrl.crowd

case class QASRLValidationPrompt[SID](
                                       genPrompt: QANomGenerationPrompt[SID],
                                       sourceHITTypeId: String,
                                       sourceHITId: String,
                                       sourceAssignmentId: List[String], // changed to contain all (aggregated) genAssignments of genHIT, todo change name
                                       genResponses: List[QANomResponse] // contain all info about generators responses
) {
  def qaPairs : List[VerbQA] = genResponses.flatMap(_.qas)
  def id : SID = genPrompt.id
  def questions = qaPairs.map(_.question)
}

case class QASRLArbitrationPrompt[SID](
                                        genPrompt: QANomGenerationPrompt[SID],
                                        genResponses: List[(String, QANomResponse)] // (GeneratorWorkerId, Response)
                                      ) {
  def id: SID = genPrompt.id

  // Provide functions that *unify* the inputs from generators.
  // From arbitrator point of view, it is provided with:
  //    * single verbForm (because it is given before generation task)
  //    * a list of isVerbal decisions
  //    * a list of QAs
  def verbForm: String = genPrompt.verbForm
  def targetIndex: Int = genPrompt.targetIndex
  def responses : List[QANomResponse] = genResponses.map(_._2)
  def isVerbals : List[Boolean] = responses.map(_.isVerbal)
  def qas : List[VerbQA] = responses.flatMap(_.qas)
  def questions : List[String] = qas.map(_.question)
}
