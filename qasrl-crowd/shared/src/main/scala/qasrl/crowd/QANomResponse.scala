package qasrl.crowd

// data from a response to a unified QANom generation assignment
case class QANomResponse(
  verbIndex: Int,
  isVerbal: Boolean,
  verbForm: String,
  qas: List[VerbQA]
)