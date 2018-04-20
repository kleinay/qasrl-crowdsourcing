
package qasrl.crowd.util

import scala.collection.JavaConverters._

object Tokenizer {
  /** Tokenizes an English string. */
  //  def tokenize(s: String): Vector[String] = {
  //    import java.io.StringReader
  //    import edu.stanford.nlp.process.PTBTokenizer
  //    import edu.stanford.nlp.process.WordTokenFactory
  //    import scala.collection.JavaConverters._
  //    new PTBTokenizer(new StringReader(s), new WordTokenFactory(), "")
  //      .tokenize.asScala.toVector.map(_.word)
  //  }

  // for using stanford NER model
  import edu.stanford.nlp.ie.crf._
  import edu.stanford.nlp.ling.CoreLabel
  import edu.stanford.nlp.ling.TaggedWord
  // todo make the classifier model available from ivy cache without having to use absolute path of server
  val NERclassifierPath= "/home/ir/kleinay/stanford_NER/stanford-ner-2018-02-27/classifiers/english.all.3class.distsim.crf.ser.gz"
  val classifier = CRFClassifier.getClassifier(NERclassifierPath)

  case class nlpWord(word:String, beginPosition:Int, endPosition:Int)

  def CoreLabeltoNlpWord(w: edu.stanford.nlp.ling.CoreLabel) : nlpWord = {
    nlpWord(w.word, w.beginPosition, w.endPosition)
  }

  /** Tokenize using a NER tokenizer for consistency */
  def tokenizeToCoreLabels(str: String): Vector[edu.stanford.nlp.ling.CoreLabel] = {
    classifier.classify(str).asScala.toList.map(_.asScala.toList).flatten.toVector
  }

  def get_NER_triplets(str : String) : List[edu.stanford.nlp.util.Triple[String,Integer,Integer]] = {
    // return a list of (String-label, start_char, end_char)
    classifier.classifyToCharacterOffsets(str).asScala.toList
  }

  def isWordInTriple(w : nlpWord, triple : edu.stanford.nlp.util.Triple[String,Integer,Integer]) : Boolean = {
    triple.second <= w.beginPosition && w.endPosition <= triple.third
  }

  def isWordLastInTriple(w : nlpWord, triple : edu.stanford.nlp.util.Triple[String,Integer,Integer]) : Boolean = {
    triple.second <= w.beginPosition && w.endPosition == triple.third
  }

  def findTripleContainingWord(w: nlpWord,
                               ner_triplets : List[edu.stanford.nlp.util.Triple[String,Integer,Integer]])
  : Option[edu.stanford.nlp.util.Triple[String,Integer,Integer]] = {
    ner_triplets.find(isWordInTriple(w, _))
  }

  def isWordInNER(w: nlpWord,
                  ner_triplets : List[edu.stanford.nlp.util.Triple[String,Integer,Integer]])
  : Boolean = {
    ner_triplets.exists(isWordInTriple(w, _))
  }

  /** Tokenizes an English string. Regard Named-Entities as single tokens. */
  def tokenize_with_ner(str: String) : Vector[String] = {

    // NER classifier also tokenizes.
    val tokenized = tokenizeToCoreLabels(str).map(CoreLabeltoNlpWord)
    val ner_triplets = get_NER_triplets(str)

    def represent_each_word(word: nlpWord) : Option[String] = {
      if (!isWordInNER(word, ner_triplets)) { Some(word.word) }
      else {
        val triple = findTripleContainingWord(word, ner_triplets).get
        if (isWordLastInTriple(word, triple))
          Some(str.slice(triple.second, triple.third))
        else None
      }
    }

    // return a vector of words, where each NER is represented as a single word
    tokenized.flatMap(represent_each_word).toVector
  }


}
