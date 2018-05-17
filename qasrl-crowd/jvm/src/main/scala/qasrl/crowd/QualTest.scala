package qasrl.crowd

trait QualTest {
  def testString: String
  def answerKeyString: String
}

object QualTest {
  import java.nio.file.Paths
  import scala.io.Source

  def getTestFromResource(testXmlFileName : String) : String = {
    val resourcePath = java.nio.file.Paths.get("resources")
    val testXmlPath = resourcePath.resolve(testXmlFileName)

    Source.fromFile(testXmlPath.toFile).mkString.trim
  }
}