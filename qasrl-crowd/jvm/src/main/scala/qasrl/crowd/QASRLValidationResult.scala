package qasrl.crowd

import spacro.Assignment

case class QASRLValidationResult[SID](
  prompt: QASRLValidationPrompt[SID],
  validatorId: String,
  response: List[QASRLValidationAnswer])

case class ValidatorBlocked(badValidatorId: String)

case class QASRLValidationFinished[SID](
  valPrompt: QASRLValidationPrompt[SID],
  validQuestions: List[String])

case class QAWSSDGenHITFinished(
  assignment: Assignment[List[VerbQA]],
  response: List[VerbQA],   // the generator's QAs
  otherResponses: List[List[VerbQA]] // the other generators' QAs
)