package com.firemoot.api

import java.time.Instant

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.auth.{ApiAuth, ApiKeys, JwtAuth}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.service.{
  ChannelService,
  MessageService,
  ModerationService,
  QueryService,
  ReactionService,
  ReadService,
  UserService,
  WebhookService,
}
import com.firemoot.testkit.Signing
import io.circe.{Encoder, Json}
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

/**
 * The client-authenticated REST surface (M4.3): an end-user JWT bearer is
 * authorised per operation against channel membership/role, with identity fields
 * forced to the token subject. Server-key (HMAC) callers keep full trust.
 */
class ClientAuthSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private def token(userId: String): String =
    JwtAuth.sign(secret, userId, None, Instant.now().plusSeconds(3600))

  private def serverPost[A: Encoder](path: String, dto: A): Request[IO] =
    Signing.signedRequest(POST, Uri.unsafeFromString(path), dto, apiKey, secret)

  private def bearer[A: Encoder](method: org.http4s.Method, path: String, dto: A, jwt: String) =
    Request[IO](method, Uri.unsafeFromString(path))
      .withEntity(dto)
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, jwt)))

  private def bearerNoBody(method: org.http4s.Method, path: String, jwt: String) =
    Request[IO](method, Uri.unsafeFromString(path))
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, jwt)))

  test("end-user JWT is authorised per operation against membership and role") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api = ApiRoutes(
            UserService(pool),
            ChannelService(pool, backplane),
            MessageService(pool, backplane),
            ReactionService(pool, backplane),
            ReadService(pool, backplane),
            QueryService(pool),
            WebhookService(pool),
            ModerationService(pool, WebhookService(pool)),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg), jwtSecret = Some(secret))(api.routes)
            .orNotFound

          val alice = token("alice")
          val bob = token("bob")
          val carol = token("carol")

          def upsert(id: String) =
            app.run(serverPost("/v1/users", UpsertUserRequest(id, None, None, None, None)))

          for
            // Seed via the server (HMAC) path: alice owns general (+bob a member);
            // bob owns a separate channel alice is not in.
            _ <- upsert("alice")
            _ <- upsert("bob")
            _ <- app.run(serverPost(
              "/v1/channels",
              CreateChannelRequest("messaging", "general", Some("alice"), None),
            ))
            _ <- app.run(serverPost(
              "/v1/channels/messaging/general/members",
              AddMemberRequest("bob", Some("member")),
            ))
            _ <- app.run(serverPost(
              "/v1/channels",
              CreateChannelRequest("messaging", "other", Some("bob"), None),
            ))

            // A member sends; identity is forced to the token subject even if the
            // body claims another user.
            sendRes <- app.run(
              bearer(
                POST,
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(Some("bob"), Some("hi"), None, None, None),
                alice,
              )
            )
            _ = assertEquals(sendRes.status, Status.Created)
            sent <- sendRes.as[Json]
            messageId = sent.hcursor.get[String]("id").toOption.get
            _ = assertEquals(
              sent.hcursor.get[String]("userId").toOption,
              Some("alice"),
              "the send is attributed to the token subject, not the body's userId",
            )

            // A non-member cannot send.
            carolSend <- app.run(
              bearer(
                POST,
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(None, Some("intruder"), None, None, None),
                carol,
              )
            )
            _ = assertEquals(carolSend.status, Status.Forbidden, "non-members are refused")

            // Only the author (or a moderator/owner) may edit a message.
            bobEdit <- app.run(
              bearer(
                PATCH,
                s"/v1/channels/messaging/general/messages/$messageId",
                EditMessageRequest(Some("hacked"), None),
                bob,
              )
            )
            _ = assertEquals(bobEdit.status, Status.Forbidden, "bob cannot edit alice's message")
            aliceEdit <- app.run(
              bearer(
                PATCH,
                s"/v1/channels/messaging/general/messages/$messageId",
                EditMessageRequest(Some("edited"), None),
                alice,
              )
            )
            _ = assertEquals(aliceEdit.status, Status.Ok, "alice edits her own message")

            // A user may only remove their own reaction.
            _ <- app.run(
              bearer(
                POST,
                s"/v1/channels/messaging/general/messages/$messageId/reactions",
                AddReactionRequest("ignored", "like"),
                alice,
              )
            )
            bobRemoves <- app.run(
              bearerNoBody(
                DELETE,
                s"/v1/channels/messaging/general/messages/$messageId/reactions/like/alice",
                bob,
              )
            )
            _ = assertEquals(
              bobRemoves.status,
              Status.Forbidden,
              "bob cannot remove alice's reaction",
            )

            // markRead is scoped and identity-forced; a member succeeds.
            read <- app.run(
              bearer(
                POST,
                "/v1/channels/messaging/general/read",
                MarkReadRequest("ignored", None),
                bob,
              )
            )
            _ = assertEquals(read.status, Status.Ok)

            // queryChannels only returns the caller's channels.
            queryRes <- app.run(
              bearer(
                POST,
                "/v1/channels/query",
                ChannelQuery(None, None, None, None, None, None, None),
                alice,
              )
            )
            channels <- queryRes.as[Json]
            cids = channels.hcursor
              .downField("channels")
              .as[List[Json]]
              .toOption
              .getOrElse(Nil)
              .flatMap(_.hcursor.get[String]("cid").toOption)
              .toSet
            _ = assertEquals(cids, Set("messaging:general"), "alice sees only her channels")

            // Server-only endpoints reject an end-user token.
            userOnAdmin <- app.run(
              bearer(
                POST,
                "/v1/channels",
                CreateChannelRequest("messaging", "sneaky", None, None),
                alice,
              )
            )
            _ = assertEquals(
              userOnAdmin.status,
              Status.Forbidden,
              "creating a channel needs a server key",
            )

            // An invalid token is rejected at the door.
            badToken <- app.run(
              bearer(
                POST,
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(None, Some("nope"), None, None, None),
                "not-a-jwt",
              )
            )
            _ = assertEquals(badToken.status, Status.Unauthorized)
          yield ()
        }
      }
    }
  }
