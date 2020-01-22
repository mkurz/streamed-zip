name := "streamed-zip"
organization := "at.irregular"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.13.0"

libraryDependencies ++= Seq(
  guice,
  "org.apache.pdfbox" % "pdfbox" % "2.0.16"
)
