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

case class QASRLGenHITFinished(
  assignment: Assignment[QANomResponse],
  response: QANomResponse,   // the generator's QAs
  otherResponses: List[QANomResponse] // the other generators' QAs
)