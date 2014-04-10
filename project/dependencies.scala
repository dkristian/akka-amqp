import sbt._

object dependencies {
  def Scalatest    = "org.scalatest" % "scalatest_2.10" % "2.0.M6-SNAP5" % "test"       // ApacheV2
  def scalaActorsForScalaTest = "org.scala-lang" % "scala-actors" % "2.10.4" % "test"
  def AmqpClient = "com.rabbitmq" % "amqp-client" % "3.3.0"                               // ApacheV2


  def AkkaAgent = "com.typesafe.akka" % "akka-agent_2.10" % "2.3.2"

  def Specs2      = "org.specs2"                 % "specs2_2.10"              % "1.13"        % "test"  // MIT
  def JUnit = "junit" % "junit" % "4.7" % "test"                                   // Common Public License 1.0
  def AkkaTestKit = "com.typesafe.akka" % "akka-testkit_2.10" % "2.2.1" % "test"
  //def ActorTests = "com.typesafe.akka" % "akka-actor-tests_2.10.0-M7" % "2.1-M2" % "test"
  def Mockito = "org.mockito" % "mockito-all" % "1.9.0" % "test"                          // MIT
}
