package qasrl.crowd

import com.amazonaws.services.mturk.model._
import com.typesafe.scalalogging.StrictLogging
import spacro.tasks.TaskConfig

import scala.collection.JavaConverters._

class QualificationService(implicit val config: TaskConfig) extends StrictLogging {

  val KEYWORDS = "language,english,question answering"

  private def findQualificationType(qualificationName: String): Option[QualificationType] = {
    val qualificationTypes = config.service.listQualificationTypes(
      new ListQualificationTypesRequest()
        .withQuery(qualificationName)
        .withMustBeOwnedByCaller(true)
        .withMustBeRequestable(false)
        .withMaxResults(100)
    ).getQualificationTypes.asScala.toList
    val found = qualificationTypes.find(_.getName == qualificationName)
    found
  }


  private def createQualification(name: String, description: String): QualificationType = {
    val qualResult = config.service.createQualificationType(
      new CreateQualificationTypeRequest()
        .withName(name)
        .withKeywords(KEYWORDS)
        .withDescription(description)
        .withQualificationTypeStatus(QualificationTypeStatus.Active)
    )
    qualResult.getQualificationType
  }

  def createQualificationReq(qualification: QualificationType, shouldHave: Boolean): QualificationRequirement = {
    val comparator = if (shouldHave) "Exists" else "DoesNotExist"
    val req = new QualificationRequirement()
      .withQualificationTypeId(qualification.getQualificationTypeId)
      .withComparator(comparator)
      .withRequiredToPreview(false)
    req
  }

  def createQualificationReq(qualification: QualificationType, isEqual: Boolean, value: Int): QualificationRequirement = {
    val comparator = if (isEqual) "EqualTo" else "NotEqualTo"
    val req = new QualificationRequirement()
      .withQualificationTypeId(qualification.getQualificationTypeId)
      .withComparator(comparator)
      .withIntegerValues(value)
      .withRequiredToPreview(false)
    req
  }

  def findOrCreate(qualName: String, description: String): QualificationType = {
    val qual = findQualificationType(qualName).getOrElse {
      logger.info(s"Generating ${qualName}")
      createQualification(qualName, description)
    }
    qual
  }

  def revokeAllWorkerQuals(qualTypeId: String, reason: String = "") = {
    val quals = config.service.listWorkersWithQualificationType(
      new ListWorkersWithQualificationTypeRequest()
        .withQualificationTypeId(qualTypeId)
        .withMaxResults(100)
    ).getQualifications.asScala.toList
    for (qual <- quals) {
      logger.info(s"Restoring qualificiation: $qualTypeId for worker: ${qual.getWorkerId}")
      config.service.disassociateQualificationFromWorker(
        new DisassociateQualificationFromWorkerRequest()
          .withQualificationTypeId(qualTypeId)
          .withWorkerId(qual.getWorkerId)
      )
    }
  }
}
