package qasrl.crowd


class QASDQuestionProcessor(sentence : String, targetWord : Word, targetType : String) {

  val questions : QASDQuestions = match targetType {
    case "noun": QASDQuestions(targetWord, QASDNounTemplates.list)
    case "adjective": QASDQuestions(targetWord, QASDAdjectiveTemplates.list)
    case "adverb": QASDQuestions(targetWord, QASDAdverbTemplates.list)
  }

  var state : String = "" // the state of current question- the text inserted so far by user

  def changeState(newState : String) : Unit = {
    state = newState
  }

  // what subset of qlist is available from current state
  def getAvailableQs : List[String] = {
    questions.qlist filter (_.startsWith(state))
  }

  // is there any possible question available for state
  def isValid : Boolean = {
    !getAvailableQs().isEmpty
  }

  // does state corresponds to an entrance of possibleQs
  def isComplete : Boolean = {
    val availables = getAvailableQs()
    availables.size == 1 &&
      questions.qlist.contains(availables(0))
  }

}
