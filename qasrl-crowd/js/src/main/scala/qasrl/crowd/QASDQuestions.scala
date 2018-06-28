package qasrl.crowd

/*
 Template syntax:

*   [W]     is replaced with the target word
*   <PREP>  is replaced with each of the prepositions
*   (word) is generating two templates, with and without the word in parantheses
*   w1/w2/../wi is generating i templates, one with each slashed word alone

 */

case class QASDQuestions(targetWord : String, targetSentence : String, templateList : List[String]) {
  // load templates from file, instantiate them with target word
  val relevantPrepositions = getRelevantPrepositions(targetSentence)
  lazy val qlist : List[String] = {
    templateList
        .map(_.replace(QASDQuestions.TRGT_SYMBOL, targetWord))  // replace TRGT_SYMBOL
        .map(replacePrep).flatten // expand PREP_SYMBOL with all prepositions
        .map(handleParatheses).flatten // replace paranthese with two templates (with\out content)
        .map(handleSlash).flatten // expand every slashed option
        .toSet.toList

  }

  def replacePrep(sentence : String) : List[String] = {
    for (prep <- relevantPrepositions.toList)
      yield sentence.replace(QASDQuestions.PREP_SYMBOL, prep)
  }

  def getRelevantPrepositions(targetSentence : String) : Set[String] = {
    // get a union of CommonPrepositions and the prepositions that occur in the original sentence
    val wordsInOrigSentence = targetSentence.split(" ").toSet

    QASDQuestions.CommonPrepositions ++
    QASDQuestions.allPreposition.intersect(wordsInOrigSentence)
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

  def handleSlash(sentence : String) : List[String] = {

    // for a token that is a regular word, return List(word);
    // for slashed words (as "a/an"), return List of the slashed words (e.g. List(a, an))
    def optionalWordsForTokens(token : String) : List[String] = {
      "/".r.findFirstMatchIn(token) match {
        case None => if (token.nonEmpty) List(token)
                      else List()
        case Some(slashMatch) => {
          val wordBeforeSlash = slashMatch.before.toString.split(" ").last
          val strAfterSlash = slashMatch.after.toString.split(" ")(0)   // this string may contain further slashes
          // Recursively remove expand slashes from strAfterSlash
          List(wordBeforeSlash) ++ optionalWordsForTokens(strAfterSlash)
        }
      }
    }

    "/".r.findFirstMatchIn(sentence) match {
      case None => List(sentence)
      case Some(slashMatch) => {
        val beforeWords = slashMatch.before.toString.split(" ")
        val prefix = beforeWords.take(beforeWords.size - 1).mkString(" ")
        val afterWords = slashMatch.after.toString.split(" ")
        val suffix = afterWords.takeRight(afterWords.size - 1).mkString(" ")
        val currentOptions = List(beforeWords.last) ++ optionalWordsForTokens(afterWords(0))
        // recursion on suffix (string sfter slashed-token, perhaps containing more slashes)
        val suffList: List[String] = handleSlash(suffix)
        for (cur <- currentOptions;
             suff <- suffList)
          yield Seq(prefix,cur,suff).mkString(" ")
      }
    }
  }

}

object QASDQuestions {
  val TRGT_SYMBOL = "[W]"
  val PREP_SYMBOL = "<PREP>"

  val CommonPrepositions =
    """in
      |on
      |to
      |for"""
      .stripMargin
      .split("\n")
      .toSet

  val allPreposition =
    """aboard
      |about
      |above
      |across
      |after
      |against
      |along
      |amid
      |among
      |anti
      |around
      |as
      |at
      |before
      |behind
      |below
      |beneath
      |beside
      |besides
      |between
      |beyond
      |but
      |by
      |concerning
      |considering
      |despite
      |down
      |during
      |except
      |excepting
      |excluding
      |following
      |for
      |from
      |in
      |inside
      |into
      |like
      |minus
      |near
      |of
      |off
      |on
      |onto
      |opposite
      |outside
      |over
      |past
      |per
      |plus
      |regarding
      |round
      |save
      |since
      |than
      |through
      |to
      |toward
      |towards
      |under
      |underneath
      |unlike
      |until
      |up
      |upon
      |versus
      |via
      |with
      |within
      |without"""
      .stripMargin
      .split("\n")
      .toSet
}
