import Dependencies.*

ThisBuild / scalaVersion := V.scala
ThisBuild / organization := "com.firemoot"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

lazy val server = project
  .in(file("server"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "firemoot-server",
    Compile / mainClass := Some("com.firemoot.Main"),
    libraryDependencies ++= Dependencies.all,
    Docker / packageName := "firemoot",
    dockerBaseImage := "eclipse-temurin:25-jre",
    dockerExposedPorts := Seq(6668),
    dockerUpdateLatest := true,
    // JDK 25 RSS profile (PLAN.md §1): compact object headers need G1, not ZGC.
    Universal / javaOptions ++= Seq(
      "-J-XX:+UseG1GC",
      "-J-XX:+UseCompactObjectHeaders",
      "-J-XX:MaxRAMPercentage=75",
    ),
  )

lazy val root = project
  .in(file("."))
  .aggregate(server)
  .settings(
    name := "firemoot",
    publish / skip := true,
  )
