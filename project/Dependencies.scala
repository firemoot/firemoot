import sbt.*

/** Single source of truth for dependency versions. Pinned 10/06/2026; see PLAN.md §1. */
object Dependencies {

  object V {
    val scala = "3.3.7"

    val http4s = "0.23.34"
    val tapir = "1.13.19"
    val sttpApiSpec = "0.11.10"
    val catsEffect = "3.7.0"
    val fs2 = "3.13.0"

    val skunk = "1.0.0"
    val flyway = "12.8.1"
    val postgres = "42.7.11"

    val awsSdk = "2.34.0"
    val twelvemonkeys = "3.12.0"

    val circe = "0.14.15"
    val ciris = "3.15.0"
    val jwtScala = "11.0.4"

    val log4cats = "2.8.0"
    val logback = "1.5.34"
    val logstash = "9.0"

    // Test
    val munit = "1.3.3"
    val munitScalacheck = "1.3.0"
    val munitCatsEffect = "2.2.0"
    val scalacheck = "1.19.0"
    val testcontainers = "0.44.1"
    val jdkHttpClient = "0.9.2"
  }

  val runtime: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-effect" % V.catsEffect,
    "co.fs2" %% "fs2-core" % V.fs2,
  )

  val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-ember-server" % V.http4s,
    "org.http4s" %% "http4s-ember-client" % V.http4s,
    "org.http4s" %% "http4s-dsl" % V.http4s,
    "org.http4s" %% "http4s-circe" % V.http4s,
  )

  val tapir: Seq[ModuleID] = Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-core" % V.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % V.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % V.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % V.tapir,
    "com.softwaremill.sttp.apispec" %% "openapi-circe" % V.sttpApiSpec,
  )

  val persistence: Seq[ModuleID] = Seq(
    "org.tpolecat" %% "skunk-core" % V.skunk,
    "org.tpolecat" %% "skunk-circe" % V.skunk,
    "org.flywaydb" % "flyway-core" % V.flyway,
    "org.flywaydb" % "flyway-database-postgresql" % V.flyway,
    "org.postgresql" % "postgresql" % V.postgres % Runtime,
  )

  // AWS SDK v2: S3 presigner (offline) + a lightweight sync client for the
  // thumbnail write-back. Generic-S3 only - no vendor admin APIs (SPEC.md §7).
  // TwelveMonkeys widens ImageIO format coverage (JPEG variants, WebP) for thumbs.
  val media: Seq[ModuleID] = Seq(
    "software.amazon.awssdk" % "s3" % V.awsSdk,
    "software.amazon.awssdk" % "url-connection-client" % V.awsSdk,
    "com.twelvemonkeys.imageio" % "imageio-jpeg" % V.twelvemonkeys,
    "com.twelvemonkeys.imageio" % "imageio-webp" % V.twelvemonkeys,
  )

  val json: Seq[ModuleID] = Seq(
    "io.circe" %% "circe-core" % V.circe,
    "io.circe" %% "circe-generic" % V.circe,
    "io.circe" %% "circe-parser" % V.circe,
  )

  val config: Seq[ModuleID] = Seq(
    "is.cir" %% "ciris" % V.ciris
  )

  val auth: Seq[ModuleID] = Seq(
    "com.github.jwt-scala" %% "jwt-circe" % V.jwtScala
  )

  val logging: Seq[ModuleID] = Seq(
    "org.typelevel" %% "log4cats-slf4j" % V.log4cats,
    "ch.qos.logback" % "logback-classic" % V.logback % Runtime,
    "net.logstash.logback" % "logstash-logback-encoder" % V.logstash % Runtime,
  )

  val tests: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit" % V.munit,
    "org.scalameta" %% "munit-scalacheck" % V.munitScalacheck,
    "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect,
    "org.scalacheck" %% "scalacheck" % V.scalacheck,
    "com.dimafeng" %% "testcontainers-scala-munit" % V.testcontainers,
    "com.dimafeng" %% "testcontainers-scala-postgresql" % V.testcontainers,
    "org.http4s" %% "http4s-jdk-http-client" % V.jdkHttpClient,
  ).map(_ % Test)

  val all: Seq[ModuleID] =
    runtime ++ http4s ++ tapir ++ persistence ++ media ++ json ++ config ++ auth ++ logging ++ tests
}
