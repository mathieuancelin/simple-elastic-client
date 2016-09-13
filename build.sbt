name := """simple-elastic-client"""
organization := "org.reactivecouchbase"
version := "1.0"
scalaVersion := "2.11.8"
crossScalaVersions := Seq("2.10.4", "2.11.8")

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.5.7",
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  "com.squareup.okhttp3" % "okhttp" % "3.4.1",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test"
)
