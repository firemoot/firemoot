import Dependencies.*

ThisBuild / scalaVersion := V.scala
ThisBuild / organization := "com.firemoot"
ThisBuild / organizationName := "Firemoot"
ThisBuild / organizationHomepage := Some(url("https://firemoot.com"))
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://firemoot.com"))
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/firemoot/firemoot"),
    "scm:git:https://github.com/firemoot/firemoot.git",
  )
)

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
    dockerLabels := Map(
      "org.opencontainers.image.title" -> "Firemoot",
      "org.opencontainers.image.description" ->
        "Self-hosted chat backend with a SaaS-grade developer experience.",
      "org.opencontainers.image.url" -> "https://firemoot.com",
      "org.opencontainers.image.source" -> "https://github.com/firemoot/firemoot",
      "org.opencontainers.image.licenses" -> "Apache-2.0",
      "org.opencontainers.image.vendor" -> "Firemoot",
      "org.opencontainers.image.version" -> version.value,
    ),
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
