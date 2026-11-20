package com.firemoot.streamapi

import java.security.MessageDigest
import java.util.Base64

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, LookupRepo, StreamStateRepo}
import com.firemoot.domain.{Channel, Event, Message}
import com.firemoot.ratelimit.{RateGuard, RateLimitDecision}
import com.firemoot.service.{
  ChannelService,
  MessageService,
  QueryService,
  ReadService,
  SendError,
  UserService,
}
import io.circe.syntax.*
import io.circe.{ACursor, Json, JsonObject}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request, Response, Status}
import skunk.Session
import skunk.data.Arr

/**
 * getstream.io's v2 REST API (`/api/v2/...`), the routes `stream-chat` v10 and
 * `@stream-io/node-sdk` call, over Firemoot's own services and data. Requests
 * authenticate with Stream's scheme ([[StreamApiAuth]]); a server token acts as
 * whichever user the request names (`user_id`, `message.user_id`,
 * `data.created_by_id`), and an end-user token acts as itself, limited to the
 * channels it is a member of, as Stream's `messaging` type allows.
 */
final class StreamApiRoutes(
    pool: Resource[IO, Session[IO]],
    auth: StreamApiAuth,
    connections: StreamConnections,
    state: StreamStateService,
    users: UserService,
    channels: ChannelService,
    messages: MessageService,
    reads: ReadService,
    query: QueryService,
    rate: RateGuard,
    backplane: Backplane,
):

  private type Result = EitherT[IO, StreamApiError, Json]

  private val DefaultChannelLimit = 10
  private val MaxChannelLimit = 30
  private val DefaultMessageLimit = 25
  private val QueryChannelsMessageLimit = 25

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" =>
      handle(req, Status.Created)(queryChannels(req, _, _))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / "delivered" =>
      handle(req, Status.Created)((_, _) => EitherT.rightT(Json.obj()))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / "query" =>
      handle(req, Status.Created)(distinct(req, _, _, t))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / "query" =>
      handle(req, Status.Created)(getOrCreate(req, _, _, t, id))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / "message" =>
      handle(req, Status.Created)(sendMessage(_, _, s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / "read" =>
      handle(req, Status.Created)(markRead(_, _, s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / "stop-watching" =>
      handle(req, Status.Created)((_, _) => stopWatching(req, s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / "event" =>
      handle(req, Status.Created)(sendEvent(_, _, s"$t:$id"))
    case req @ PATCH -> Root / "api" / "v2" / "chat" / "channels" / t / id =>
      handle(req, Status.Ok)(updatePartial(_, _, s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id =>
      handle(req, Status.Created)(update(_, _, s"$t:$id"))
    case req @ DELETE -> Root / "api" / "v2" / "chat" / "channels" / t / id =>
      handle(req, Status.Ok)((p, _) => deleteChannel(p, s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "users" =>
      handle(req, Status.Created)(upsertUsers(_, _))
    case req @ GET -> Root / "api" / "v2" / "users" =>
      handle(req, Status.Ok)((_, _) => queryUsers(req))
  }

  // --- plumbing ---

  private def handle(req: Request[IO], success: Status)(
      op: (StreamPrincipal, ACursor) => Result
  ): IO[Response[IO]] =
    val started = System.nanoTime()
    val body: IO[Json] =
      if req.method == GET || req.method == DELETE then IO.pure(Json.obj())
      else
        req.bodyText.compile.string.map(raw =>
          if raw.isBlank then Json.obj() else io.circe.parser.parse(raw).getOrElse(Json.Null)
        )
    (for
      principal <- EitherT(auth.authenticate(req))
      json <- EitherT.liftF(body)
      _ <- EitherT.cond[IO](json.isObject, (), StreamApiError.input("Request", "invalid JSON body"))
      result <- op(principal, json.hcursor)
    yield result).value.map {
      case Left(error) => error.response
      case Right(json) =>
        val millis = (System.nanoTime() - started) / 1_000_000.0
        Response[IO](success).withEntity(json.deepMerge(Json.obj("duration" ->
          f"$millis%.2fms".asJson)))
    }

  private def fail[A](error: StreamApiError): EitherT[IO, StreamApiError, A] = EitherT.leftT(error)

  private def str(c: ACursor, field: String): Option[String] =
    c.downField(field).as[String].toOption.filter(_.nonEmpty)

  private def bool(c: ACursor, field: String): Option[Boolean] =
    c.downField(field).as[Boolean].toOption

  /** The acting user: the token's own, or for a server token the first id the request names. */
  private def actor(
      op: String,
      p: StreamPrincipal,
      named: Option[String]*
  ): EitherT[IO, StreamApiError, String] =
    p match
      case StreamPrincipal.User(id) => EitherT.rightT(id)
      case StreamPrincipal.Server =>
        EitherT.fromOption(
          named.flatten.headOption,
          StreamApiError.input(
            op,
            "either user or user_id must be provided when using server side auth",
          ),
        )

  private def userOnly(p: StreamPrincipal): Option[String] = p match
    case StreamPrincipal.User(id) => Some(id)
    case StreamPrincipal.Server => None

  private def existing(op: String, cid: String): EitherT[IO, StreamApiError, Channel] =
    EitherT.fromOptionF(
      channels.get(cid),
      StreamApiError.notFound(op, s"channel \"$cid\" doesn't exist"),
    )

  /** End users need membership (`roles` restricts it further); server tokens always pass. */
  private def permitted(
      op: String,
      action: String,
      p: StreamPrincipal,
      cid: String,
      roles: Set[String] = Set.empty,
  )
      : EitherT[IO, StreamApiError, Option[String]] =
    p match
      case StreamPrincipal.Server => EitherT.rightT(None)
      case StreamPrincipal.User(id) =>
        EitherT(channels.memberRole(cid, id).map {
          case Some(role) if roles.isEmpty || roles(role) => Right(Some(role))
          case _ =>
            val scope = cid.takeWhile(_ != ':')
            Left(StreamApiError.notAllowed(
              op,
              s"User '$id' with role 'user' is not allowed to perform action $action in scope '$scope'",
            ))
        })

  private val moderators = Set("owner", "moderator")

  private def limited(op: String, decision: RateLimitDecision): EitherT[IO, StreamApiError, Unit] =
    decision match
      case RateLimitDecision.Allowed => EitherT.rightT(())
      case RateLimitDecision.Retry(_) => fail(StreamApiError.rateLimited(op))

  /** `[{user_id, channel_role?}]` or bare ids, as `(userId, firemoot role)`. */
  private def memberRequests(json: Option[Json]): List[(String, String)] =
    json.flatMap(_.asArray).toList.flatten.flatMap { m =>
      m.asString.map(_ -> "member").orElse(
        m.hcursor.get[String]("user_id").toOption.map { id =>
          val role = m.hcursor.get[String]("channel_role").toOption match
            case Some("channel_moderator") => "moderator"
            case Some("channel_owner") => "owner"
            case _ => "member"
          id -> role
        }
      )
    }

  private def requireUsers(op: String, ids: List[String]): EitherT[IO, StreamApiError, Unit] =
    val distinct = ids.distinct
    if distinct.isEmpty then EitherT.rightT(())
    else
      EitherT(pool.use(_.runList(LookupRepo.users, Arr(distinct*))).map { found =>
        val missing = distinct.filterNot(found.map(_.id).toSet)
        Either.cond(
          missing.isEmpty,
          (),
          StreamApiError.input(
            op,
            s"The following users are involved in channel create operation, but don't exist: [${missing.mkString(", ")}]. Please create the user objects before setting up the channel.",
          ),
        )
      })

  private def stateOf(
      channel: Channel,
      caller: Option[String],
      paging: Option[MessagePaging],
  ): IO[Json] =
    pool.use { s =>
      paging.fold(IO.pure(List.empty[Message]))(state.page(s, channel.cid, _)).flatMap { page =>
        state.states(s, List(channel), caller, Map(channel.cid -> page)).map(_.head)
      }
    }

  private def channelJson(channel: Channel, caller: Option[String]): IO[Json] =
    stateOf(channel, caller, None).map { st =>
      Json.obj(
        "channel" -> st.hcursor.downField("channel").focus.getOrElse(Json.Null),
        "members" -> st.hcursor.downField("members").focus.getOrElse(Json.arr()),
      )
    }

  // --- channels ---

  private def queryChannels(req: Request[IO], p: StreamPrincipal, body: ACursor): Result =
    val op = "QueryChannels"
    val limit = body.downField(
      "limit"
    ).as[Int].toOption.getOrElse(DefaultChannelLimit).max(1).min(MaxChannelLimit)
    val offset = body.downField("offset").as[Int].toOption.getOrElse(0).max(0)
    val messageLimit = body.downField("message_limit").as[Int].toOption
      .getOrElse(QueryChannelsMessageLimit).max(0).min(MessagePaging.MaxLimit)
    val withState = bool(body, "state").getOrElse(true)
    for
      filter <- EitherT.fromEither[IO](StreamFilters.channelQuery(
        body.downField("filter_conditions").focus,
        op,
      ))
      caller = userOnly(p)
      scoped = caller.fold(filter)(u =>
        filter.copy(members =
          Some(filter.members.fold(List(u))(ms => if ms.contains(u) then List(u) else ms))
        )
      )
      page <- EitherT.liftF(query.channels(scoped.copy(limit = Some(limit + offset))))
      visible <- EitherT.liftF(caller.fold(IO.pure(page.channels)) { u =>
        page.channels.filterA(c => channels.memberRole(c.cid, u).map(_.isDefined))
      })
      chosen = visible.drop(offset).take(limit)
      _ <- EitherT.liftF(watchAll(req, p, body, chosen.map(_.cid), default = true))
      states <- EitherT.liftF(pool.use { s =>
        (if withState then state.newestPages(s, chosen.map(_.cid), messageLimit)
         else IO.pure(Map.empty))
          .flatMap(pages => state.states(s, chosen, caller, pages))
      })
    yield Json.obj("channels" -> states.asJson)

  /** Subscribes the requesting socket when the request watches (`watch` defaults per call). */
  private def watchAll(
      req: Request[IO],
      p: StreamPrincipal,
      body: ACursor,
      cids: List[String],
      default: Boolean,
  )
      : IO[Unit] =
    (userOnly(p), req.params.get("connection_id")) match
      case (Some(u), Some(conn)) if bool(body, "watch").getOrElse(default) =>
        cids.traverse_(connections.watch(conn, u, _))
      case _ => IO.unit

  /** Stream's distinct channels: an id derived from the sorted member set. */
  private def distinct(
      req: Request[IO],
      p: StreamPrincipal,
      body: ACursor,
      channelType: String,
  ): Result =
    val members =
      memberRequests(body.downField("data").downField("members").focus).map(_._1).distinct.sorted
    if members.isEmpty then
      fail(StreamApiError.input(
        "GetOrCreateChannel",
        "When channel id is not provided, members must be specified",
      ))
    else
      val digest =
        MessageDigest.getInstance("SHA-256").digest(members.mkString(",").getBytes("UTF-8"))
      val id = "!members-" + Base64.getUrlEncoder.withoutPadding.encodeToString(digest).take(43)
      getOrCreate(req, p, body, channelType, id)

  private def getOrCreate(
      req: Request[IO],
      p: StreamPrincipal,
      body: ACursor,
      channelType: String,
      id: String,
  )
      : Result =
    val op = "GetOrCreateChannel"
    val cid = s"$channelType:$id"
    val data = body.downField("data")
    for
      found <- EitherT.liftF(channels.get(cid))
      channel <-
        found.fold(create(op, p, data, channelType, id))(c => EitherT.rightT[IO, StreamApiError](c))
      _ <- permitted(op, "ReadChannel", p, cid)
      _ <- EitherT.liftF(watchAll(req, p, body, List(cid), default = false))
      withState = bool(body, "state").getOrElse(false)
      paging = Option.when(withState)(MessagePaging.from(
        body.downField("messages").focus,
        DefaultMessageLimit,
      ))
      json <- EitherT.liftF(stateOf(channel, userOnly(p), paging))
    yield json

  private def create(op: String, p: StreamPrincipal, data: ACursor, channelType: String, id: String)
      : EitherT[IO, StreamApiError, Channel] =
    val cid = s"$channelType:$id"
    val members = memberRequests(data.downField("members").focus)
    val custom = data.downField("custom").focus.filter(_.isObject).getOrElse(Json.obj())
    for
      creator <- p match
        case StreamPrincipal.User(u) => EitherT.rightT[IO, StreamApiError](u)
        case StreamPrincipal.Server =>
          EitherT.fromOption[IO](
            str(data, "created_by_id").orElse(str(data.downField("created_by"), "id")),
            StreamApiError.input(
              op,
              "either data.created_by or data.created_by_id must be provided when using server side auth",
            ),
          )
      _ <- requireUsers(op, creator :: members.map(_._1))
      inserted <- EitherT.liftF(pool.use(_.runOption(
        ChannelRepo.insertIfAbsent,
        (cid, channelType, id, Some(creator), custom),
      )))
      channel <- inserted match
        case Some(c) =>
          EitherT.liftF(members.traverse_ { (userId, role) =>
            channels.addMember(
              cid,
              userId,
              if userId == creator && role == "member" then "owner" else role,
            )
          }.as(c))
        case None => existing(op, cid)
    yield channel

  private def sendMessage(p: StreamPrincipal, body: ACursor, cid: String): Result =
    val op = "SendMessage"
    val msg = body.downField("message")
    for
      _ <- EitherT.cond[IO](
        msg.focus.exists(_.isObject),
        (),
        StreamApiError.input(op, "message is a required field"),
      )
      userId <- actor(op, p, str(msg, "user_id"), str(msg.downField("user"), "id"))
      _ <- existing(op, cid)
      _ <- permitted(op, "SendMessage", p, cid)
      decision <- EitherT.liftF(p match
        case StreamPrincipal.Server => rate.serverSend(userId)
        case StreamPrincipal.User(_) => rate.send(userId))
      _ <- limited(op, decision)
      messageType = str(msg, "type").filter(t =>
        t == "system" && p == StreamPrincipal.Server
      ).getOrElse("regular")
      sent <- EitherT.liftF(messages.send(
        cid = cid,
        userId = Some(userId),
        text = msg.downField("text").as[String].toOption,
        custom = msg.downField("custom").focus.filter(_.isObject).getOrElse(Json.obj()),
        attachments = msg.downField("attachments").focus.filter(_.isArray).getOrElse(Json.arr()),
        parentMessageId = str(msg, "parent_id"),
        messageType = messageType,
        id = str(msg, "id"),
      ))
      message <- EitherT.fromEither[IO](sent.leftMap {
        case SendError.ChannelNotFound =>
          StreamApiError.notFound(op, s"channel \"$cid\" doesn't exist")
        case SendError.ChannelFrozen => StreamApiError.notAllowed(op, "channel is frozen")
        case SendError.DuplicateId(id) =>
          StreamApiError.input(op, s"message with id $id already exists")
      })
      json <- EitherT.liftF(pool.use(s =>
        state.users(s, List(userId)).map(us =>
          V2Json.message(message, us, MessageReactions.empty)
        )
      ))
    yield Json.obj("message" -> json)

  private def markRead(p: StreamPrincipal, body: ACursor, cid: String): Result =
    val op = "MarkRead"
    for
      userId <- actor(op, p, str(body, "user_id"), str(body.downField("user"), "id"))
      _ <- existing(op, cid)
      _ <- permitted(op, "ReadChannel", p, cid)
      upTo <- str(body, "message_id").fold(EitherT.rightT[IO, StreamApiError](Option.empty[Long])) {
        mid =>
          EitherT.fromOptionF(
            pool.use(_.runOption(StreamStateRepo.seqOf, (cid, mid))).map(_.map(Some(_))),
            StreamApiError.notFound(op, s"message with id $mid doesn't exist"),
          )
      }
      _ <- EitherT.fromOptionF(
        reads.markRead(cid, userId, upTo),
        StreamApiError.notAllowed(op, s"user $userId is not a member of channel $cid"),
      )
    yield Json.obj()

  private def stopWatching(req: Request[IO], cid: String): Result =
    EitherT.liftF(req.params.get("connection_id").traverse_(connections.unwatch(
      _,
      cid,
    )).as(Json.obj()))

  private def sendEvent(p: StreamPrincipal, body: ACursor, cid: String): Result =
    val op = "SendEvent"
    val event = body.downField("event")
    for
      eventType <- EitherT.fromOption[IO](
        str(event, "type"),
        StreamApiError.input(op, "event.type is a required field"),
      )
      userId <- actor(op, p, str(event, "user_id"), str(event.downField("user"), "id"))
      _ <- existing(op, cid)
      _ <- permitted(op, "SendCustomEvent", p, cid)
      data = Json.obj(
        "userId" -> userId.asJson,
        "parentId" -> str(event, "parent_id").asJson,
        "custom" -> event.downField("custom").focus.filter(_.isObject).getOrElse(Json.obj()),
      ).dropNullValues
      _ <- EitherT.liftF(backplane.publish(Event(eventType, cid, 0L, data)))
      at <- EitherT.liftF(IO.realTimeInstant.map(_.atOffset(java.time.ZoneOffset.UTC)))
      rendered <- EitherT.liftF(pool.use(s =>
        state.users(s, List(userId)).map { us =>
          StreamEvents.base(eventType, cid, at).deepMerge(Json.obj(
            "user" -> us(userId),
            "custom" -> data.hcursor.downField("custom").focus.getOrElse(Json.obj()),
          ))
        }
      ))
    yield Json.obj("event" -> rendered)

  /** Built-in channel fields a partial update may set; anything else is custom data. */
  private val builtInChannelFields =
    Set(
      "frozen",
      "disabled",
      "cooldown",
      "team",
      "auto_translation_enabled",
      "auto_translation_language",
    )

  private def updatePartial(p: StreamPrincipal, body: ACursor, cid: String): Result =
    val op = "UpdateChannelPartial"
    val set = body.downField("set").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
    val unset = body.downField("unset").as[List[String]].toOption.getOrElse(Nil)
    for
      _ <- EitherT.cond[IO](
        !set.contains("custom"),
        (),
        StreamApiError.input(op, "custom is a reserved field"),
      )
      current <- existing(op, cid)
      _ <- permitted(op, "UpdateChannel", p, cid, moderators)
      custom = current.custom.asObject.getOrElse(JsonObject.empty)
      merged = unset.foldLeft(set.filterKeys(k => !builtInChannelFields(k)).toList.foldLeft(custom)(
        (o, kv) => o.add(kv._1, kv._2)
      ))(_.remove(_))
      frozen =
        set("frozen").flatMap(_.asBoolean).orElse(Option.when(unset.contains("frozen"))(false))
      updated <- EitherT.fromOptionF(
        channels.update(cid, Some(Json.fromJsonObject(merged)), frozen, None),
        StreamApiError.notFound(op, s"channel \"$cid\" doesn't exist"),
      )
      json <- EitherT.liftF(channelJson(updated, userOnly(p)))
    yield json

  private def update(p: StreamPrincipal, body: ACursor, cid: String): Result =
    val op = "UpdateChannel"
    val add = memberRequests(body.downField("add_members").focus)
    val remove = body.downField("remove_members").as[List[String]].toOption.getOrElse(Nil)
    val data = body.downField("data")
    for
      _ <- existing(op, cid)
      _ <- permitted(op, "UpdateChannelMembers", p, cid, moderators)
      _ <- requireUsers(op, add.map(_._1))
      _ <- EitherT.liftF(add.traverse_((u, role) => channels.addMember(cid, u, role)))
      _ <- EitherT.liftF(remove.traverse_(channels.removeMember(cid, _)))
      _ <- EitherT.liftF(
        if data.focus.exists(_.isObject) then
          channels.update(
            cid,
            data.downField("custom").focus.filter(_.isObject),
            bool(data, "frozen"),
            None,
          ).void
        else IO.unit
      )
      current <- existing(op, cid)
      json <- EitherT.liftF(channelJson(current, userOnly(p)))
    yield json

  private def deleteChannel(p: StreamPrincipal, cid: String): Result =
    val op = "DeleteChannel"
    for
      _ <- existing(op, cid)
      _ <- permitted(op, "DeleteChannel", p, cid, moderators)
      _ <- EitherT.liftF(channels.softDelete(cid))
      deleted <- EitherT.liftF(pool.use(_.runOption(ChannelRepo.anyByCid, cid)))
      json <- deleted.fold(EitherT.rightT[IO, StreamApiError](Json.obj())) { c =>
        EitherT.liftF(pool.use(s =>
          state.users(s, c.createdBy.toList).map(us =>
            Json.obj("channel" -> V2Json.channel(c, us, 0L, None, None))
          )
        ))
      }
    yield json

  // --- users ---

  private def upsertUsers(p: StreamPrincipal, body: ACursor): Result =
    val op = "UpdateUsers"
    val requested = body.downField("users").focus.flatMap(_.asObject).toList.flatMap(_.toList)
    for
      _ <- EitherT.cond[IO](
        requested.nonEmpty,
        (),
        StreamApiError.input(op, "users is a required field"),
      )
      _ <- p match
        case StreamPrincipal.Server => EitherT.rightT[IO, StreamApiError](())
        case StreamPrincipal.User(me) =>
          EitherT.cond[IO](
            requested.forall(_._1 == me),
            (),
            StreamApiError.notAllowed(
              op,
              s"User '$me' with role 'user' is not allowed to perform action UpdateUser",
            ),
          )
      saved <- EitherT.liftF(requested.traverse { (key, u) =>
        val c = u.hcursor
        val id = c.get[String]("id").toOption.getOrElse(key)
        val role = p match
          case StreamPrincipal.Server => c.get[String]("role").toOption.getOrElse("user")
          case StreamPrincipal.User(_) => "user"
        users.upsert(
          id,
          c.get[String]("name").toOption,
          c.get[String]("image").toOption,
          role,
          c.downField("custom").focus.filter(_.isObject).getOrElse(Json.obj()),
        )
      })
      online <- EitherT.liftF(state.online)
    yield Json.obj("users" -> Json.fromFields(saved.map(u => u.id -> V2Json.user(u, online(u.id)))))

  private def queryUsers(req: Request[IO]): Result =
    val op = "QueryUsers"
    val payload = req.params.get(
      "payload"
    ).flatMap(io.circe.parser.parse(_).toOption).getOrElse(Json.obj()).hcursor
    val limit = payload.downField("limit").as[Int].toOption.getOrElse(30).max(1).min(100)
    val offset = payload.downField("offset").as[Int].toOption.getOrElse(0).max(0)
    val newestFirst = payload.downField("sort").as[List[Json]].toOption.toList.flatten.headOption
      .flatMap(_.hcursor.get[Int]("direction").toOption).contains(-1)
    for
      ids <- EitherT.fromEither[IO](StreamFilters.userIds(
        payload.downField("filter_conditions").focus,
        op,
      ))
      found <- EitherT.liftF(pool.use(s =>
        ids match
          case Some(list) =>
            if list.isEmpty then IO.pure(Nil)
            else
              s.runList(
                LookupRepo.users,
                Arr(list*),
              ).map(_.sortBy(_.createdAt).drop(offset).take(limit))
          case None =>
            s.runList(
              if newestFirst then StreamStateRepo.usersNewestFirst
              else StreamStateRepo.usersOldestFirst,
              (limit, offset),
            )
      ))
      online <- EitherT.liftF(state.online)
    yield Json.obj("users" -> found.map(u => V2Json.user(u, online(u.id))).asJson)
