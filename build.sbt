name := """simple-elastic-client"""
organization := "org.reactivecouchbase"
version := "1.1.3"
scalaVersion := "2.11.8"
crossScalaVersions := Seq("2.10.4", "2.11.8")

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.5.13",
  "ch.qos.logback" % "logback-classic" % "1.2.1",
  "com.squareup.okhttp3" % "okhttp" % "3.6.0",
  "com.typesafe.akka" %% "akka-stream" % "2.4.17",

  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "org.elasticsearch" % "elasticsearch" % "2.3.2" % "test",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.3" % "test",
  "net.java.dev.jna" % "jna" % "4.1.0" % "test"
)


val local: Project.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
  val localPublishRepo = "./repository"
  if(version.trim.endsWith("SNAPSHOT"))
    Some(Resolver.file("snapshots", new File(localPublishRepo + "/snapshots")))
  else Some(Resolver.file("releases", new File(localPublishRepo + "/releases")))
}

publishTo <<= local
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
pomExtra := (
  <url>https://github.com/mathieuancelin/simple-elastic-client</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:mathieuancelin/simple-elastic-client.git</url>
    <connection>scm:git:git@github.com:mathieuancelin/simple-elastic-client.git</connection>
  </scm>
  <developers>
    <developer>
      <id>mathieu.ancelin</id>
      <name>Mathieu ANCELIN</name>
      <url>https://github.com/mathieuancelin</url>
    </developer>
  </developers>
)