name := "legmon"
organization := "uk.dryark"
version := "1.0.0"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "ujson" % "3.1.3",
  "com.lihaoyi" %% "requests" % "0.8.0",
  "org.apache.pdfbox" % "pdfbox" % "2.0.31",
  "ch.qos.logback" % "logback-classic" % "1.2.13",
  "org.slf4j" % "slf4j-api" % "1.7.36"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

mainClass in (Compile, run) := Some("legmon.Main")
mainClass in assembly := Some("legmon.Main")
