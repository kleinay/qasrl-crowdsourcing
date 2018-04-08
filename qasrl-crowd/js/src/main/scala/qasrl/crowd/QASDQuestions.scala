package qasrl.crowd

case class QASDQuestions(targetWord : String, templateList : List[String]) {
  // load templates from file, instantiate them with target word
  lazy val qlist : List[String] = {
    templateList map (_.replace(QASDQuestions.TRGT_SYMBOL, targetWord))
    // TODO expand PREP_SYMBOL with all prepositions
  }
  
}

object QASDQuestions {
  val TRGT_SYMBOL = "[W]"
  val PREP_SYMBOL = "<PREP>"
}
