package com.firemoot.api

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.auth.{ApiAuth, ApiKeys}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.{Channel, Message, User}
import com.firemoot.service.{
  ChannelService,
  HydrationService,
  MessageService,
  ModerationService,
  QueryService,
  ReactionService,
  ReadService,
  UserService,
  WebhookService,
}
import com.firemoot.testkit.Signing
import io.circe.Encoder
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.{Status, Uri}
import org.testcontainers.utility.DockerImageName

class ApiSuite extends CatsEffectSuite, TestContainerForAll:

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

  private def post[A: Encoder](path: String, dto: A, signingSecret: String = secret) =
    Signing.signedRequest(POST, Uri.unsafeFromString(path), dto, apiKey, signingSecret)

  test("users, channels and messages: happy path, seq allocation, auth, openapi") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api =
            ApiRoutes(
              UserService(pool),
              ChannelService(pool, backplane),
              MessageService(pool, backplane),
              ReactionService(pool, backplane),
              ReadService(pool, backplane),
              QueryService(pool),
              WebhookService(pool),
              ModerationService(pool, WebhookService(pool)),
              HydrationService(pool),
            )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound

          for
            userRes <- app.run(post(
              "/v1/users",
              UpsertUserRequest("alice", Some("Alice"), None, None, None),
            ))
            _ = assertEquals(userRes.status, Status.Ok)
            user <- userRes.as[User]
            _ = assertEquals(user.id, "alice")
            _ = assertEquals(user.name, Some("Alice"))

            chRes <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "general", Some("alice"), None),
            ))
            _ = assertEquals(chRes.status, Status.Created)
            channel <- chRes.as[Channel]
            _ = assertEquals(channel.cid, "messaging:general")
            _ = assertEquals(channel.currentSeq, 0L)

            m1Res <- app.run(
              post(
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(Some("alice"), Some("hello"), None, None, None),
              )
            )
            _ = assertEquals(m1Res.status, Status.Created)
            m1 <- m1Res.as[Message]
            _ = assertEquals(m1.seq, 1L)
            _ = assertEquals(m1.text, Some("hello"))

            m2Res <- app.run(
              post(
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(Some("alice"), Some("world"), None, None, None),
              )
            )
            m2 <- m2Res.as[Message]
            _ = assertEquals(m2.seq, 2L)

            badSig <- app.run(post(
              "/v1/users",
              UpsertUserRequest("bob", None, None, None, None),
              signingSecret = "wrong",
            ))
            _ = assertEquals(badSig.status, Status.Unauthorized)

            delOk <- app.run(Signing.signedNoBody(DELETE, uri"/v1/users/alice", apiKey, secret))
            _ = assertEquals(delOk.status, Status.NoContent)
            delMissing <-
              app.run(Signing.signedNoBody(DELETE, uri"/v1/users/alice", apiKey, secret))
            _ = assertEquals(delMissing.status, Status.NotFound)
          yield
            assert(OpenApiDocs.compact.contains("/v1/users"), "openapi should document /v1/users")
            assert(OpenApiDocs.compact.contains("Firemoot"), "openapi should carry the title")
        }
      }
    }
  }

  test("channel endpoints: get / update / members / frozen / delete status codes") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api =
            ApiRoutes(
              UserService(pool),
              ChannelService(pool, backplane),
              MessageService(pool, backplane),
              ReactionService(pool, backplane),
              ReadService(pool, backplane),
              QueryService(pool),
              WebhookService(pool),
              ModerationService(pool, WebhookService(pool)),
              HydrationService(pool),
            )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val chPath = "/v1/channels/messaging/room2"
          def get(path: String) =
            Signing.signedNoBody(GET, Uri.unsafeFromString(path), apiKey, secret)
          def del(path: String) =
            Signing.signedNoBody(DELETE, Uri.unsafeFromString(path), apiKey, secret)
          def send[A: Encoder](method: org.http4s.Method, path: String, dto: A) =
            Signing.signedRequest(method, Uri.unsafeFromString(path), dto, apiKey, secret)

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("carol", None, None, None, None)))
            missing <- app.run(get(chPath))
            _ = assertEquals(missing.status, Status.NotFound)
            created <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "room2", Some("carol"), None),
            ))
            _ = assertEquals(created.status, Status.Created)
            got <- app.run(get(chPath))
            _ = assertEquals(got.status, Status.Ok)
            badRole <-
              app.run(send(POST, s"$chPath/members", AddMemberRequest("dave", Some("king"))))
            _ = assertEquals(badRole.status, Status.BadRequest)
            _ <- app.run(post("/v1/users", UpsertUserRequest("dave", None, None, None, None)))
            addOk <-
              app.run(send(POST, s"$chPath/members", AddMemberRequest("dave", Some("moderator"))))
            _ = assertEquals(addOk.status, Status.NoContent)
            removeOk <- app.run(del(s"$chPath/members/dave"))
            _ = assertEquals(removeOk.status, Status.NoContent)
            frozen <- app.run(send(PATCH, chPath, UpdateChannelRequest(None, Some(true), None)))
            _ = assertEquals(frozen.status, Status.Ok)
            sendFrozen <- app.run(send(
              POST,
              s"$chPath/messages",
              SendMessageRequest(Some("carol"), Some("hi"), None, None, None),
            ))
            _ = assertEquals(sendFrozen.status, Status.Conflict)
            deleted <- app.run(del(chPath))
            _ = assertEquals(deleted.status, Status.NoContent)
            gone <- app.run(get(chPath))
          yield assertEquals(gone.status, Status.NotFound)
        }
      }
    }
  }

  test("message endpoints: edit / delete / system / invalid type status codes") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api =
            ApiRoutes(
              UserService(pool),
              ChannelService(pool, backplane),
              MessageService(pool, backplane),
              ReactionService(pool, backplane),
              ReadService(pool, backplane),
              QueryService(pool),
              WebhookService(pool),
              ModerationService(pool, WebhookService(pool)),
              HydrationService(pool),
            )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val msgs = "/v1/channels/messaging/room3/messages"
          def at(path: String, dto: EditMessageRequest) =
            Signing.signedRequest(PATCH, Uri.unsafeFromString(path), dto, apiKey, secret)
          def del(path: String) =
            Signing.signedNoBody(DELETE, Uri.unsafeFromString(path), apiKey, secret)
          val random = java.util.UUID.randomUUID()

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("erin", None, None, None, None)))
            _ <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "room3", Some("erin"), None),
            ))
            sent <-
              app.run(post(msgs, SendMessageRequest(Some("erin"), Some("hi"), None, None, None)))
            _ = assertEquals(sent.status, Status.Created)
            msg <- sent.as[Message]
            edited <- app.run(at(s"$msgs/${msg.id}", EditMessageRequest(Some("edited"), None)))
            _ = assertEquals(edited.status, Status.Ok)
            editMissing <- app.run(at(s"$msgs/$random", EditMessageRequest(Some("x"), None)))
            _ = assertEquals(editMissing.status, Status.NotFound)
            badType <- app.run(post(
              msgs,
              SendMessageRequest(Some("erin"), Some("x"), None, None, None, Some("weird")),
            ))
            _ = assertEquals(badType.status, Status.BadRequest)
            system <- app.run(post(
              msgs,
              SendMessageRequest(None, Some("erin joined"), None, None, None, Some("system")),
            ))
            _ = assertEquals(system.status, Status.Created)
            deleted <- app.run(del(s"$msgs/${msg.id}"))
            _ = assertEquals(deleted.status, Status.NoContent)
            deleteMissing <- app.run(del(s"$msgs/$random"))
          yield assertEquals(deleteMissing.status, Status.NotFound)
        }
      }
    }
  }

  test("client-supplied message ids: round-trip, before_id cursor, duplicate 409, invalid 400") {
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
            HydrationService(pool),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val msgs = "/v1/channels/messaging/cid1/messages"
          def get(path: String) =
            Signing.signedNoBody(GET, Uri.unsafeFromString(path), apiKey, secret)
          def sendWith(id: Option[String], text: String) =
            SendMessageRequest(Some("nate"), Some(text), None, None, None, None, id)

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("nate", None, None, None, None)))
            _ <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "cid1", Some("nate"), None),
            ))

            firstRes <- app.run(post(msgs, sendWith(Some("cmrxyz_first"), "first")))
            _ = assertEquals(firstRes.status, Status.Created)
            first <- firstRes.as[Message]
            _ = assertEquals(first.id, "cmrxyz_first", "the caller's id round-trips on the wire")

            secondRes <- app.run(post(msgs, sendWith(Some("cmrxyz_second"), "second")))
            _ = assertEquals(secondRes.status, Status.Created)

            beforeIdRes <- app.run(get(s"$msgs?before_id=cmrxyz_second"))
            _ = assertEquals(beforeIdRes.status, Status.Ok)
            beforeId <- beforeIdRes.as[MessagePage]
            _ = assertEquals(
              beforeId.messages.map(_.text),
              List(Some("first")),
              "before_id resolves a client-supplied id to its seq",
            )

            dupRes <- app.run(post(msgs, sendWith(Some("cmrxyz_first"), "again")))
            _ = assertEquals(dupRes.status, Status.Conflict)
            dupBody <- dupRes.bodyText.compile.string
            _ = assert(
              dupBody.contains("already exists") && dupBody.contains("cmrxyz_first"),
              s"the 409 detail names the id and says 'already exists': $dupBody",
            )

            blankRes <- app.run(post(msgs, sendWith(Some(""), "blank")))
            _ = assertEquals(blankRes.status, Status.BadRequest, "an empty id is a 400")
            wsRes <- app.run(post(msgs, sendWith(Some("has space"), "ws")))
            _ = assertEquals(wsRes.status, Status.BadRequest, "whitespace in an id is a 400")
            longRes <- app.run(post(msgs, sendWith(Some("x" * 256), "long")))
          yield assertEquals(longRes.status, Status.BadRequest, "an over-length id is a 400")
        }
      }
    }
  }

  test("global message delete: resolves the channel, soft-deletes, 404 unknown") {
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
            HydrationService(pool),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val msgs = "/v1/channels/messaging/cid2/messages"
          def get(path: String) =
            Signing.signedNoBody(GET, Uri.unsafeFromString(path), apiKey, secret)
          def del(path: String) =
            Signing.signedNoBody(DELETE, Uri.unsafeFromString(path), apiKey, secret)

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("olive", None, None, None, None)))
            _ <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "cid2", Some("olive"), None),
            ))
            sent <- app.run(post(
              msgs,
              SendMessageRequest(Some("olive"), Some("bye"), None, None, None),
            ))
            msg <- sent.as[Message]

            deleted <- app.run(del(s"/v1/messages/${msg.id}"))
            _ =
              assertEquals(deleted.status, Status.NoContent, "delete by id alone resolves the cid")

            hist <- app.run(get(s"$msgs")).flatMap(_.as[MessagePage])
            _ = assertEquals(hist.messages, Nil, "the soft-deleted message is gone from history")

            deleteAgain <- app.run(del(s"/v1/messages/${msg.id}"))
            _ = assertEquals(deleteAgain.status, Status.NotFound, "a re-delete is a 404")
            deleteUnknown <- app.run(del("/v1/messages/no-such-message"))
          yield assertEquals(deleteUnknown.status, Status.NotFound, "an unknown id is a 404")
        }
      }
    }
  }

  test("reaction endpoints: add / remove / counts / missing message") {
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
            HydrationService(pool),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val msgs = "/v1/channels/messaging/room4/messages"

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("frank", None, None, None, None)))
            _ <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "room4", Some("frank"), None),
            ))
            sent <-
              app.run(post(msgs, SendMessageRequest(Some("frank"), Some("hi"), None, None, None)))
            msg <- sent.as[Message]

            added <-
              app.run(post(s"$msgs/${msg.id}/reactions", AddReactionRequest("frank", "like")))
            _ = assertEquals(added.status, Status.Ok)
            addedSummary <- added.as[ReactionSummary]
            _ = assertEquals(addedSummary.counts, Map("like" -> 1L))

            removed <- app.run(
              Signing.signedNoBody(
                DELETE,
                Uri.unsafeFromString(s"$msgs/${msg.id}/reactions/like/frank"),
                apiKey,
                secret,
              )
            )
            _ = assertEquals(removed.status, Status.Ok)
            removedSummary <- removed.as[ReactionSummary]
            _ = assertEquals(removedSummary.counts, Map.empty[String, Long])

            missing <- app.run(post(
              s"$msgs/${java.util.UUID.randomUUID()}/reactions",
              AddReactionRequest("frank", "like"),
            ))
          yield assertEquals(missing.status, Status.NotFound)
        }
      }
    }
  }

  test("read endpoint: markRead status codes and membership") {
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
            HydrationService(pool),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("grace", None, None, None, None)))
            _ <- app.run(post("/v1/users", UpsertUserRequest("helen", None, None, None, None)))
            _ <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "room5", Some("grace"), None),
            ))

            missingChannel <-
              app.run(post("/v1/channels/messaging/ghost/read", MarkReadRequest("grace", None)))
            _ = assertEquals(missingChannel.status, Status.NotFound)
            nonMember <-
              app.run(post("/v1/channels/messaging/room5/read", MarkReadRequest("helen", None)))
            _ = assertEquals(nonMember.status, Status.NotFound)

            ok <- app.run(post("/v1/channels/messaging/room5/read", MarkReadRequest("grace", None)))
            _ = assertEquals(ok.status, Status.Ok)
            state <- ok.as[ReadStateResponse]
          yield assertEquals(state.unreadCount, 0L)
        }
      }
    }
  }

  test("query, history and search endpoints are wired through the stack") {
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
            HydrationService(pool),
          )
          val app = ApiAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val msgs = "/v1/channels/qt/qroom/messages"
          def get(path: String) =
            Signing.signedNoBody(GET, Uri.unsafeFromString(path), apiKey, secret)

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("ivy", None, None, None, None)))
            _ <-
              app.run(post("/v1/channels", CreateChannelRequest("qt", "qroom", Some("ivy"), None)))
            m1Res <- app.run(post(
              msgs,
              SendMessageRequest(Some("ivy"), Some("hello world"), None, None, None),
            ))
            m1 <- m1Res.as[Message]
            m2Res <- app.run(post(
              msgs,
              SendMessageRequest(Some("ivy"), Some("second message"), None, None, None),
            ))
            m2 <- m2Res.as[Message]

            queryRes <- app.run(post(
              "/v1/channels/query",
              ChannelQuery(Some("qt"), None, None, None, None, None, None),
            ))
            _ = assertEquals(queryRes.status, Status.Ok)
            page <- queryRes.as[ChannelStatePage]
            _ = assertEquals(page.channels.map(_.channel.cid), List("qt:qroom"))
            _ = assertEquals(
              page.channels.head.latestMessage.flatMap(_.text),
              Some("second message"),
              "the server-key query is hydrated with the latest message",
            )
            _ = assertEquals(page.channels.head.read, None, "a server-key caller has no read state")

            histRes <- app.run(get(s"$msgs?limit=1"))
            _ = assertEquals(histRes.status, Status.Ok)
            hist <- histRes.as[MessagePage]
            _ = assertEquals(hist.messages.map(_.text), List(Some("second message")))
            _ = assert(hist.nextBeforeSeq.isDefined, "a full page yields a cursor")

            beforeIdRes <- app.run(get(s"$msgs?before_id=${m2.id}"))
            _ = assertEquals(beforeIdRes.status, Status.Ok)
            beforeId <- beforeIdRes.as[MessagePage]
            _ = assertEquals(
              beforeId.messages.map(_.text),
              List(Some("hello world")),
              "before_id returns messages strictly older than the cursor",
            )
            unknownIdRes <- app.run(get(s"$msgs?before_id=${java.util.UUID.randomUUID()}"))
            _ = assertEquals(
              unknownIdRes.status,
              Status.NotFound,
              "an unknown before_id is a 404, not an empty page",
            )
            bothCursorsRes <- app.run(get(s"$msgs?before_seq=99&before_id=${m2.id}"))
            _ = assertEquals(
              bothCursorsRes.status,
              Status.BadRequest,
              "before_seq and before_id together is a 400",
            )

            searchRes <- app.run(post("/v1/search", SearchRequest("hello", Some("qt:qroom"), None)))
            _ = assertEquals(searchRes.status, Status.Ok)
            hits <- searchRes.as[SearchPage]
            _ = assertEquals(hits.hits.map(_.message.text), List(Some("hello world")))

            flagRes <-
              app.run(post(s"$msgs/${m1.id}/flag", FlagMessageRequest("ivy", Some("spam"))))
            _ = assertEquals(flagRes.status, Status.Created)
            flag <- flagRes.as[Flag]
            _ = assertEquals(flag.flaggedUser, Some("ivy"))
            flagsRes <- app.run(get("/v1/moderation/flags"))
            _ = assertEquals(flagsRes.status, Status.Ok)
            flags <- flagsRes.as[List[Flag]]
          yield assertEquals(flags.map(_.messageId), List(m1.id))
        }
      }
    }
  }
