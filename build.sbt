import Dependencies.*

ThisBuild / scalaVersion := V.scala
ThisBuild / organization := "com.firemoot"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

lazy val server = project
  .in(file("server"))
  .settings(
    name := "firemoot-server",
    Compile / mainClass := Some("com.firemoot.Main"),
    libraryDependencies ++= Dependencies.all,
  )

lazy val root = project
  .in(file("."))
  .aggregate(server)
  .settings(
    name := "firemoot",
    publish / skip := true,
  )
