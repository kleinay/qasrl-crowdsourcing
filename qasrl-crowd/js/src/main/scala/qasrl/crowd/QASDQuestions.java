package qasrl.crowd

case class QASDQuestions(targetWord : String, templatesFileName : String) {
  // load templates from file, instantiate them with target word
  lazy val qlist : List[String] = {
    val templateSource = scala.io.Source.fromFile(templatesFileName)
    val templateList = try source.getLines.toList finally templateSource.close()
    templateList map (_.replace(TRGT_SYMBOL, targetWord))

    // TODO expand <PREP> with all prepositions
  }
  
}

object QASDQuestions {
  val TRGT_SYMBOL = "[W]"
  val 
}
