package qasrl.crowd

/*
 Template syntax:

*   [W]     is replaced with the target word
*   <PREP>  is replaced with each of the prepositions
*   (word) is generating two templates, with and without the word in parantheses

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

  // recursive function to expand all optional (parantheses) parts in templates
  def handleParatheses(sentence : String) : List[String] = {
    val paranRegex = """\([^()]*\)""".r
    def inside(s: String): String = s.slice(1, s.size - 1)
    var validPrefixes = List[String]()
    paranRegex.findFirstMatchIn(sentence) match {
      case None => List(sentence)
      case Some(firstMatch) => {
        val suffix: String = firstMatch.after.toString
        val prnContent: String = inside(firstMatch.toString)
        val prefix: String = firstMatch.before.toString
        // return both options for prefix with\out parantheses with each suffix option
        val suffList: List[String] = handleParatheses(suffix)
        (for (suff <- suffList)
          yield List(prefix + suff, prefix + prnContent + suff)
          ).flatten
      }
    }
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
