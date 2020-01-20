package eval

case class SentenceId(id: String)

object SentenceId {

  // not necessarily used for serialization over the wire, but
  // used for storing to / reading from  the dataset file.
  def toString(sid: SentenceId) = sid.id.toString
  def fromString(s: String): SentenceId = SentenceId(s)
}
