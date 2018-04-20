package qasrl.crowd.util

import scala.collection.JavaConverters._


import nlpdata.structure.Word

object PosTagger {

  import edu.stanford.nlp.tagger.maxent.MaxentTagger
  lazy val tagger: MaxentTagger  = new MaxentTagger("edu/stanford/nlp/models/pos-tagger/english-left3words/english-left3words-distsim.tagger");

//  val posTagCache = collection.mutable.Map.empty[Vector[String], Vector[Word]]
//
  import cats.Foldable
  import cats.implicits._
//  /** POS-tags a sequence of tokens. */
//  def posTag[F[_]: Foldable](s: F[String]): Vector[Word] = {
//    val origTokens = s.toList.toVector // to prevent americanization.
//    // probably we can do that with a tagger parameter...but...how about later..
//    posTagCache.get(origTokens) match {
//      case None =>
//        val result = tagger.tagTokenizedString(s.toList.mkString(" ")).split(" ").toVector
//          .map(_.split("_"))
//          .map(s => (s(0), s(1)))
//          .zipWithIndex
//          .map { case ((token, pos), index) => Word(
//                  token = token,
//                  pos = pos,
//                  index = index)
//        }
//        posTagCache.put(origTokens, result)
//        result
//      case Some(result) => result
//    }
//  }
  def posTag[F[_]: Foldable](s: F[String]): Vector[Word] = {
    val str = s.toList.mkString(" ")
    val ner_triplets = Tokenizer.get_NER_triplets(str)
    val tokenized = Tokenizer.tokenizeToCoreLabels(str)
    val pos_tagged = tagger.tagSentence(tokenized.asJava).asScala.toVector

    // todo handle indexed - need to re-index
    def represent_each_tagged_word(taggedWord: edu.stanford.nlp.ling.TaggedWord, sentence:String) : Option[(String, String)] = {
      val word=Tokenizer.nlpWord(taggedWord.word, taggedWord.beginPosition, taggedWord.endPosition)
      if (!Tokenizer.isWordInNER(word, ner_triplets)) {
        Some((taggedWord.word, taggedWord.tag))
      }
      else {
        // word inside NamedEntity, take only last
        val triple = Tokenizer.findTripleContainingWord(word, ner_triplets).get
        val namedEntityString = sentence.slice(triple.second, triple.third)
        if (Tokenizer.isWordLastInTriple(word, triple))
          Some((namedEntityString, "NNP"))
        else
          None
      }
    }

    pos_tagged.flatMap(represent_each_tagged_word(_, str))
      .zipWithIndex
      .map { case ((token, pos), index) => Word(
        token = token,
        pos = pos,
        index = index) }
      .toVector

  }
}
