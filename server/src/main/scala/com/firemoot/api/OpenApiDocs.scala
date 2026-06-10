package com.firemoot.api

import io.circe.syntax.*
import sttp.apispec.openapi.circe.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

/**
 * The OpenAPI 3.1 document for the REST surface - the single source of truth for
 * the generated `@firemoot/core` TypeScript SDK (M0.8). Pure: depends only on the
 * endpoint definitions, so it can be exported without booting the server.
 */
object OpenApiDocs:

  private val document =
    OpenAPIDocsInterpreter().toOpenAPI(ApiEndpoints.all, "Firemoot", "0.1.0")

  val compact: String = document.asJson.deepDropNullValues.noSpaces
  val pretty: String = document.asJson.deepDropNullValues.spaces2
