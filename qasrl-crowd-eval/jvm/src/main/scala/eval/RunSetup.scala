package eval

import java.nio.file.Paths

import qasrl.crowd.Stage
import spacro._
import spacro.tasks._

import scala.concurrent.duration._

object RunSetup extends App {
  val isProduction = false // sandbox. change to true for production
  val domain = "localhost" // change to your domain, or keep localhost for testing
  val projectName = "qasrl-crowd-eval" // make sure it matches the SBT project;
  // this is how the .js file is found to send to the server

  val interface = "0.0.0.0"
  val httpPort = 8888
  val httpsPort = 8080

  // Uncomment the phase you want to activate
  //val phase = Trap
//  val phase = Training
  val phase = Stage.Production

  val phaseName = phase.toString.toLowerCase
  val annotationPath = Paths.get(s"data/annotations.$phaseName")
  val liveDataPath = Paths.get(s"data/live.$phaseName")
  val sentsPath = Paths.get(s"data/$phaseName.csv")
  val qasrlPath = Paths.get(s"data/$phaseName.annot.csv")

  implicit val timeout = akka.util.Timeout(5.seconds)
  implicit val config: TaskConfig = {
    if(isProduction) {
      val hitDataService = new FileSystemHITDataService(annotationPath.resolve("production"))
      ProductionTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
    } else {
      val hitDataService = new FileSystemHITDataService(annotationPath.resolve("sandbox"))
      SandboxTaskConfig(projectName, domain, interface, httpPort, httpsPort, hitDataService)
    }
  }
  val numEvalsPerPrompt = 1
  val setup = new EvaluationSetup(qasrlPath, Some(sentsPath), liveDataPath, phase, numEvalsPerPrompt)

  val exp = setup.experiment
  exp.start()

}
