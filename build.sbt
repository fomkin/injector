lazy val root = project.
  aggregate(injectorJS, injectorJVM).
  settings(
    publish := {},
    publishLocal := {}
  )

lazy val injector = crossProject.crossType(CrossType.Pure).in(file(".")).
  settings(
    scalaVersion := "2.11.7",
    organization := "com.github.fomkin",
    name := "injector",
    version := "0.1.0-SNAPSHOT",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Xfatal-warnings"
    ),
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value) Some("snapshots" at s"${nexus}content/repositories/snapshots")
      else Some("releases" at s"${nexus}service/local/staging/deploy/maven2")
    },
    pomExtra := {
      <url>https://github.com/fomkin/inject</url>
        <licenses>
          <license>
            <name>Apache License, Version 2.0</name>
            <url>http://apache.org/licenses/LICENSE-2.0</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:fomkin/injector.git</url>
          <connection>scm:git:git@github.com:fomkin/injector.git</connection>
        </scm>
        <developers>
          <developer>
            <id>fomkin</id>
            <name>Aleksey Fomkin</name>
            <email>aleksey.fomkin@gmail.com</email>
          </developer>
        </developers>
    }
  )

lazy val injectorJS = injector.js
lazy val injectorJVM = injector.jvm
