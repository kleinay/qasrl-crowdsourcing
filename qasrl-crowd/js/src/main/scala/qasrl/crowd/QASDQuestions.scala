package qasrl.crowd

/*
 Template syntax:

*   [W]     is replaced with the target word
*   <PREP>  is replaced with each of the prepositions
*   (word) is generating two templates, with and without the word in parantheses (only one allowed in single template)

 */

case class QASDQuestions(targetWord : String, templateList : List[String]) {
  // load templates from file, instantiate them with target word
  lazy val qlist : List[String] = {
    templateList
        .map(_.replace(QASDQuestions.TRGT_SYMBOL, targetWord))  // replace TRGT_SYMBOL
        .map(replacePrep).flatten // expand PREP_SYMBOL with all prepositions
        .map(handleParatheses).flatten // replace paranthese with two templates (with\out content)
        .toSet.toList

  }

  def replacePrep(sentence : String) : List[String] = {
    for (prep <- QASDQuestions.Prepositions)
      yield sentence.replace(QASDQuestions.PREP_SYMBOL, prep)
  }

  def handleParatheses(sentence : String) : List[String] = {
    val sentWithoutPrn = sentence.replaceAll("\\(.*?\\)", "");
    val sentWithPrn = sentence.replace("(", "").replace(")", "")
    Set(sentWithPrn, sentWithoutPrn).toList
  }


}

object QASDQuestions {
  val TRGT_SYMBOL = "[W]"
  val PREP_SYMBOL = "<PREP>"

  val Prepositions =
    """on
      |under
      |with
      |above
      |in
      |over
      |for"""
      .stripMargin
      .split("\n")
      .toList

}
