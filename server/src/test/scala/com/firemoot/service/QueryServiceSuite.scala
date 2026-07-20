package com.firemoot.service

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForEach
import com.firemoot.api.{ChannelPage, ChannelQuery, SearchRequest}
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

/**
 * The M1.9 query surface: channel filtering (and its injection-safety),
 * cursor pagination, message history, and full-text search. A fresh database
 * per test keeps the data sets cleanly isolated.
 */
class QueryServiceSuite extends CatsEffectSuite, TestContainerForEach:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private def emptyQuery: ChannelQuery = ChannelQuery(None, None, None, None, None, None, None)
  private def cidsOf(page: ChannelPage): Set[String] = page.channels.map(_.cid).toSet

  /** Spins up services against a fresh, migrated database. */
  private def withServices[A](
      pg: PostgreSQLContainer
  )(f: (UserService, ChannelService, MessageService, QueryService) => IO[A]): IO[A] =
    val cfg = dbConfig(pg)
    Backplane.inProcess.flatMap { backplane =>
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        f(
          UserService(pool),
          ChannelService(pool, backplane),
          MessageService(pool, backplane),
          QueryService(pool),
        )
      }
    }

  test("channel filters: type, cids, members, custom containment, archived") {
    withContainers { pg =>
      withServices(pg) { (users, channels, _, queries) =>
        def custom(team: String) = Json.obj("team" -> team.asJson)
        for
          _ <- List("alice", "bob", "carol").traverse_(u =>
            users.upsert(u, None, None, "user", Json.obj())
          )
          _ <- channels.create("fmsg", "general", Some("alice"), custom("red"))
          _ <- channels.addMember("fmsg:general", "bob", "member")
          _ <- channels.create("fmsg", "random", Some("bob"), custom("blue"))
          _ <- channels.create("flive", "event", Some("carol"), custom("red"))
          _ <- channels.create("fteam", "secret", Some("alice"), custom("red"))
          _ <- channels.update("fteam:secret", None, None, Some(true))

          byType <- queries.channels(emptyQuery.copy(`type` = Some("fmsg")))
          byCids <- queries.channels(
            emptyQuery.copy(cids = Some(List("fmsg:general", "flive:event")))
          )
          byAlice <- queries.channels(emptyQuery.copy(members = Some(List("alice"))))
          byBob <- queries.channels(emptyQuery.copy(members = Some(List("bob"))))
          byCustom <- queries.channels(emptyQuery.copy(custom = Some(custom("red"))))
          byCustomType <- queries.channels(
            emptyQuery.copy(custom = Some(custom("red")), `type` = Some("fmsg"))
          )
          archived <- queries.channels(emptyQuery.copy(archived = Some(true)))
          active <- queries.channels(emptyQuery.copy(archived = Some(false)))
        yield
          assertEquals(cidsOf(byType), Set("fmsg:general", "fmsg:random"))
          assertEquals(cidsOf(byCids), Set("fmsg:general", "flive:event"))
          assertEquals(cidsOf(byAlice), Set("fmsg:general", "fteam:secret"))
          assertEquals(cidsOf(byBob), Set("fmsg:general", "fmsg:random"))
          assertEquals(cidsOf(byCustom), Set("fmsg:general", "flive:event", "fteam:secret"))
          assertEquals(cidsOf(byCustomType), Set("fmsg:general"))
          assertEquals(cidsOf(archived), Set("fteam:secret"))
          assertEquals(cidsOf(active), Set("fmsg:general", "fmsg:random", "flive:event"))
      }
    }
  }

  test("channel filter values are treated as literals, not SQL (injection-safe)") {
    withContainers { pg =>
      withServices(pg) { (users, channels, _, queries) =>
        val evilType = "' or '1'='1"
        val evilCustomVal = "v'); drop table channels; --"
        for
          _ <- users.upsert("u", None, None, "user", Json.obj())
          _ <- channels.create("safe", "one", Some("u"), Json.obj())
          _ <- channels.create("safe", "two", Some("u"), Json.obj())
          // A channel whose type is literally an injection string.
          _ <- channels.create(evilType, "x", Some("u"), Json.obj())
          _ <-
            channels.create("safe", "evilcustom", Some("u"), Json.obj("k" -> evilCustomVal.asJson))

          // Each adversarial value must match only its literal, never act as SQL.
          orInjection <- queries.channels(emptyQuery.copy(`type` = Some(evilType)))
          dropInjection <- queries.channels(
            emptyQuery.copy(`type` = Some("'; drop table channels; --"))
          )
          wildcard <- queries.channels(emptyQuery.copy(`type` = Some("%")))
          underscore <- queries.channels(emptyQuery.copy(`type` = Some("saf_")))
          customLiteral <- queries.channels(
            emptyQuery.copy(custom = Some(Json.obj("k" -> evilCustomVal.asJson)))
          )
          // Canary: the table is intact and ordinary filtering still works.
          canary <- queries.channels(emptyQuery.copy(`type` = Some("safe")))
        yield
          assertEquals(
            cidsOf(orInjection),
            Set(s"$evilType:x"),
            "the OR string matches its literal type",
          )
          assertEquals(dropInjection.channels, Nil, "the DROP string is just a non-matching value")
          assertEquals(wildcard.channels, Nil, "% is a literal, not a LIKE wildcard")
          assertEquals(underscore.channels, Nil, "_ is a literal, not a LIKE wildcard")
          assertEquals(cidsOf(customLiteral), Set("safe:evilcustom"))
          assertEquals(
            cidsOf(canary),
            Set("safe:one", "safe:two", "safe:evilcustom"),
            "channels table survived every adversarial query",
          )
      }
    }
  }

  test("channel query paginates by cursor in most-recent-activity order") {
    withContainers { pg =>
      withServices(pg) { (users, channels, messages, queries) =>
        val ids = (1 to 5).map(i => s"pg:c$i").toList
        for
          _ <- users.upsert("p", None, None, "user", Json.obj())
          _ <- (1 to 5).toList.traverse_ { i =>
            channels.create("pg", s"c$i", Some("p"), Json.obj()) >>
              // A message per channel sets last_message_at; sleep keeps them distinct.
              messages.send(
                s"pg:c$i",
                Some("p"),
                Some(s"hi $i"),
                Json.obj(),
                Json.arr(),
                None,
                "regular",
              ) >>
              IO.sleep(8.millis)
          }

          page1 <- queries.channels(emptyQuery.copy(`type` = Some("pg"), limit = Some(2)))
          page2 <- queries.channels(
            emptyQuery.copy(`type` = Some("pg"), limit = Some(2), cursor = page1.nextCursor)
          )
          page3 <- queries.channels(
            emptyQuery.copy(`type` = Some("pg"), limit = Some(2), cursor = page2.nextCursor)
          )
        yield
          val ordered = (page1.channels ++ page2.channels ++ page3.channels).map(_.cid)
          // c5 was last to receive a message, so it sorts first; c1 last.
          assertEquals(ordered, ids.reverse, "every channel once, newest activity first")
          assertEquals(page1.channels.size, 2)
          assertEquals(page2.channels.size, 2)
          assertEquals(page3.channels.size, 1, "final page is partial")
          assertEquals(page3.nextCursor, None, "no cursor once the last page is short")
      }
    }
  }

  test("message history paginates by seq, newest first, excluding deleted") {
    withContainers { pg =>
      withServices(pg) { (users, channels, messages, queries) =>
        val cid = "mh:room"
        def send(i: Int) =
          messages
            .send(cid, Some("h"), Some(s"m$i"), Json.obj(), Json.arr(), None, "regular")
            .map(_.toOption.get)
        for
          _ <- users.upsert("h", None, None, "user", Json.obj())
          _ <- channels.create("mh", "room", Some("h"), Json.obj())
          sent <- (1 to 6).toList.traverse(send)
          _ <- messages.delete(cid, sent(2).id) // delete "m3"

          page1 <- queries.messageHistory(cid, None, Some(2))
          page2 <- queries.messageHistory(cid, page1.nextBeforeSeq, Some(2))
          rest <- queries.messageHistory(cid, page2.nextBeforeSeq, Some(10))
        yield
          val texts = (page1.messages ++ page2.messages ++ rest.messages).map(_.text)
          // Newest first; m3 is deleted so it's absent.
          assertEquals(
            texts,
            List(Some("m6"), Some("m5"), Some("m4"), Some("m2"), Some("m1")),
          )
          val seqs = (page1.messages ++ page2.messages ++ rest.messages).map(_.seq)
          assertEquals(seqs, seqs.sortBy(-_), "strictly descending by seq")
          assertEquals(rest.nextBeforeSeq, None)
      }
    }
  }

  test("message history resolves a before_id cursor to its seq, then paginates before it") {
    withContainers { pg =>
      withServices(pg) { (users, channels, messages, queries) =>
        val cid = "mhid:room"
        def send(i: Int) =
          messages
            .send(cid, Some("h"), Some(s"m$i"), Json.obj(), Json.arr(), None, "regular")
            .map(_.toOption.get)
        for
          _ <- users.upsert("h", None, None, "user", Json.obj())
          _ <- channels.create("mhid", "room", Some("h"), Json.obj())
          sent <- (1 to 5).toList.traverse(send)
          cursor = sent(3) // "m4"
          seq <- queries.messageSeq(cid, cursor.id)
          missing <- queries.messageSeq(cid, "no-such-message")
          page <- queries.messageHistory(cid, seq, Some(2))
        yield
          assertEquals(seq, Some(cursor.seq), "resolves the cursor message's seq")
          assertEquals(missing, None, "an unknown id resolves to nothing")
          // Strictly before m4, newest first, clamped to the limit: m3, m2.
          assertEquals(page.messages.map(_.text), List(Some("m3"), Some("m2")))
          assert(page.nextBeforeSeq.isDefined, "a full page yields a further cursor")
      }
    }
  }

  test("full-text search ranks matches, honours the cid filter and websearch syntax") {
    withContainers { pg =>
      withServices(pg) { (users, channels, messages, queries) =>
        val a = "srch:a"
        val b = "srch:b"
        def send(
            cid: String,
            text: String,
            msgType: String = "regular",
            author: Option[String] = Some("s"),
        ) =
          messages.send(
            cid,
            author,
            Some(text),
            Json.obj(),
            Json.arr(),
            None,
            msgType,
          ).map(_.toOption.get)
        for
          _ <- users.upsert("s", None, None, "user", Json.obj())
          _ <- channels.create("srch", "a", Some("s"), Json.obj())
          _ <- channels.create("srch", "b", Some("s"), Json.obj())
          _ <- send(a, "the quick brown fox")
          _ <- send(a, "lazy dog sleeps")
          _ <- send(a, "quick fox runs")
          _ <- send(a, "quick start guide")
          _ <- send(a, "system quick note", msgType = "system", author = None)
          _ <- send(b, "quick remote message")

          quickFox <- queries.search(SearchRequest("quick fox", Some(a), None))
          quickNotFox <- queries.search(SearchRequest("quick -fox", Some(a), None))
          quickInA <- queries.search(SearchRequest("quick", Some(a), None))
          quickAnywhere <- queries.search(SearchRequest("quick", None, None))
          stemmed <- queries.search(SearchRequest("running", None, None))
        yield
          def texts(p: com.firemoot.api.SearchPage) = p.hits.map(_.message.text.getOrElse("")).toSet

          assertEquals(texts(quickFox), Set("the quick brown fox", "quick fox runs"))
          assertEquals(texts(quickNotFox), Set("quick start guide"), "websearch exclusion works")
          assertEquals(
            texts(quickInA),
            Set("the quick brown fox", "quick fox runs", "quick start guide"),
            "system messages are excluded and the cid filter scopes results",
          )
          assert(
            texts(quickAnywhere).contains("quick remote message"),
            "without a cid filter, other channels are included",
          )
          assertEquals(stemmed.hits, Nil, "the 'simple' config does not stem (runs != running)")
          assert(quickFox.hits.forall(_.score > 0.0), "hits carry a positive rank")
          val scores = quickInA.hits.map(_.score)
          assertEquals(scores, scores.sortBy(-_), "results are ranked, highest first")
      }
    }
  }
