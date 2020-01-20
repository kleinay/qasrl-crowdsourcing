package qasrl

import spacro.tasks.ResponseRW

import nlpdata.datasets.wiktionary.InflectedForms

package object crowd {

  case class QANomGenerationPrompt[SID](
                                         id: SID,
                                         targetIndex: Int,
                                         verbForm: String)

  case class GenerationStatSummary(
    numVerbsCompleted: Int, // before validation: used to calculate coverage
    numQuestionsWritten: Int, // before validation: "
    workerStatsOpt: Option[QASRLGenerationWorkerStats])

  case class QANomGenerationAjaxRequest[SID](
    workerIdOpt: Option[String],
    prompt: QANomGenerationPrompt[SID]) {
    type Response = QANomGenerationAjaxResponse
  }
  object QANomGenerationAjaxRequest {
    import upickle.default._
    implicit def responseRW[SID] = new ResponseRW[QANomGenerationAjaxRequest[SID]] {
      override def getReader(request: QANomGenerationAjaxRequest[SID]) =
        implicitly[Reader[QANomGenerationAjaxResponse]]
      override def getWriter(request: QANomGenerationAjaxRequest[SID]) =
        implicitly[Writer[QANomGenerationAjaxResponse]]
    }
  }

  case class QANomGenerationAjaxResponse(
    stats: GenerationStatSummary,
    tokens: Vector[String],
    inflectedForms: Option[InflectedForms])

  case class QASRLValidationAjaxRequest[SID](
    workerIdOpt: Option[String],
    id: SID) {
    type Response = QASRLValidationAjaxResponse
  }
  object QASRLValidationAjaxRequest {
    import upickle.default._
    implicit def responseRW[SID] = new ResponseRW[QASRLValidationAjaxRequest[SID]] {
      override def getReader(request: QASRLValidationAjaxRequest[SID]) =
        implicitly[Reader[QASRLValidationAjaxResponse]]
      override def getWriter(request: QASRLValidationAjaxRequest[SID]) =
        implicitly[Writer[QASRLValidationAjaxResponse]]
    }
  }

  case class QASRLValidationAjaxResponse(
    workerInfoOpt: Option[QASRLValidationWorkerInfoSummary],
    sentence: Vector[String])

  import nlpdata.util.LowerCaseStrings._

  implicit val lowerCaseStringReader = upickle.default.Reader[LowerCaseString] {
    case upickle.Js.Str(s) => s.lowerCase // just for typing. whatever
  }
  implicit val lowerCaseStringWriter = upickle.default.Writer[LowerCaseString] {
    case s => upickle.Js.Str(s.toString)
  }

}
