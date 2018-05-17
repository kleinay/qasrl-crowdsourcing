package example

import qasrl.crowd.QualTest

object SDValQualTestExample extends QualTest {
  override val testString = QualTest.getTestFromResource("sdvalSpanQualTest.xml")

  private[this] def answerXML(qid: String, aid: String): String = answerXML(qid, List(aid))
  private[this] def answerXML(qid: String, aids: List[String]): String = {
    val opts = aids.map(aid => s"""
<AnswerOption>
  <SelectionIdentifier>$aid</SelectionIdentifier>
  <AnswerScore>1</AnswerScore>
</AnswerOption>
""".trim).mkString("\n")
    s"""
<Question>
<QuestionIdentifier>$qid</QuestionIdentifier>
$opts
</Question>
""".trim

  }
  override val answerKeyString = s"""<?xml version="1.0" encoding="UTF-8"?>
<AnswerKey xmlns="http://mechanicalturk.amazonaws.com/AWSMechanicalTurkDataSchemas/2005-10-01/AnswerKey.xsd">
${answerXML("q1", "q1-a2")}
${answerXML("q2", "q2-invalid")}
${answerXML("q3", "q3-a3")}
<QualificationValueMapping>
  <PercentageMapping>
    <MaximumSummedScore>14</MaximumSummedScore>
  </PercentageMapping>
</QualificationValueMapping>
</AnswerKey>
""".trim



}