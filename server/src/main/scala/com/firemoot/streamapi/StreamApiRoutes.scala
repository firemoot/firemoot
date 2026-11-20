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
import com.firemoot.api.SearchRequest
import com.firemoot.media.{DirectUploads, UploadError}
import com.firemoot.service.{
  ChannelService,
  MessageService,
  ModerationService,
  QueryService,
  ReactionService,
  ReadService,
  SendError,
  UserService,
}
import io.circe.syntax.*
import io.circe.{ACursor, Json, JsonObject}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.{EntityDecoder, HttpRoutes, Request, Response, Status}
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
    reactions: ReactionService,
    moderation: ModerationService,
    uploads: Option[DirectUploads],
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
    case req @ GET -> Root / "api" / "v2" / "chat" / "messages" / id / "replies" =>
      handle(req, Status.Ok)((p, _) => replies(req, p, id))
    case req @ GET -> Root / "api" / "v2" / "chat" / "messages" / id / "reactions" =>
      handle(req, Status.Ok)((p, _) => listReactions(req, p, id))
    case req @ POST -> Root / "api" / "v2" / "chat" / "messages" / id / "reaction" =>
      handle(req, Status.Created)(sendReaction(_, _, id))
    case req @ DELETE -> Root / "api" / "v2" / "chat" / "messages" / id / "reaction" / t =>
      handle(req, Status.Ok)((p, _) => deleteReaction(req, p, id, t))
    case req @ GET -> Root / "api" / "v2" / "chat" / "messages" / id =>
      handle(req, Status.Ok)((p, _) => getMessage(p, id))
    case req @ POST -> Root / "api" / "v2" / "chat" / "messages" / id =>
      handle(req, Status.Created)(updateMessage(_, _, id))
    case req @ PUT -> Root / "api" / "v2" / "chat" / "messages" / id =>
      handle(req, Status.Created)(updateMessagePartial(_, _, id))
    case req @ DELETE -> Root / "api" / "v2" / "chat" / "messages" / id =>
      handle(req, Status.Ok)((p, _) => deleteMessage(p, id))
    case req @ GET -> Root / "api" / "v2" / "chat" / "channels" / t / id / "messages" =>
      handle(req, Status.Ok)((p, _) => getManyMessages(req, p, s"$t:$id"))
    case req @ GET -> Root / "api" / "v2" / "chat" / "search" =>
      handle(req, Status.Ok)((p, _) => search(req, p))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / t / id / kind
        if kind == "image" || kind == "file" =>
      upload(req, Some(s"$t:$id"))
    case req @ POST -> Root / "api" / "v2" / "uploads" / kind
        if kind == "image" || kind == "file" =>
      upload(req, None)
    case req @ DELETE -> Root / "api" / "v2" / "chat" / "channels" / _ / _ / kind
        if kind == "image" || kind == "file" =>
      handle(req, Status.Ok)((_, _) => EitherT.rightT(Json.obj()))
    case req @ DELETE -> Root / "api" / "v2" / "uploads" / kind
        if kind == "image" || kind == "file" =>
      handle(req, Status.Ok)((_, _) => EitherT.rightT(Json.obj()))
    case req @ POST -> Root / "api" / "v2" / "moderation" / "flag" =>
      handle(req, Status.Created)(flag(_, _))
    case req @ POST -> Root / "api" / "v2" / "users" / "delete" =>
      handle(req, Status.Created)(deleteUsers(_, _))
    case req @ POST -> Root / "api" / "v2" / "chat" / "channels" / "delete" =>
      handle(req, Status.Created)(deleteChannels(_, _))
    case req @ GET -> Root / "api" / "v2" / "tasks" / id =>
      handle(req, Status.Ok)((_, _) => getTask(id))
    case req @ GET -> Root / "api" / "v2" / "app" =>
      handle(req, Status.Ok)((_, _) => EitherT.rightT(appSettings))
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

  // --- messages ---

  private def message(op: String, id: String): EitherT[IO, StreamApiError, Message] =
    EitherT.fromOptionF(
      pool.use(_.runOption(LookupRepo.message, id)),
      StreamApiError.notFound(op, s"message with id $id doesn't exist"),
    )

  private def messageJson(m: Message, caller: Option[String]): IO[Json] =
    pool.use { s =>
      state.reactions(s, List(m.id), caller).flatMap { reacts =>
        val r = reacts.getOrElse(m.id, MessageReactions.empty)
        state.users(s, m.userId.toList ++ (r.latest ++ r.own).map(_.userId))
          .map(us => V2Json.message(m, us, r))
      }
    }

  /** The author, or a channel owner/moderator; server tokens always pass. */
  private def mayModify(op: String, action: String, p: StreamPrincipal, m: Message)
      : EitherT[IO, StreamApiError, Unit] =
    p match
      case StreamPrincipal.Server => EitherT.rightT(())
      case StreamPrincipal.User(id) if m.userId.contains(id) => permitted(op, action, p, m.cid).void
      case StreamPrincipal.User(_) => permitted(op, action, p, m.cid, moderators).void

  private def getMessage(p: StreamPrincipal, id: String): Result =
    val op = "GetMessage"
    for
      m <- message(op, id)
      _ <- permitted(op, "ReadChannel", p, m.cid)
      json <- EitherT.liftF(messageJson(m, userOnly(p)))
    yield Json.obj("message" -> json)

  private def getManyMessages(req: Request[IO], p: StreamPrincipal, cid: String): Result =
    val op = "GetManyMessages"
    val ids = req.params.get("ids").toList.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)
    for
      _ <- existing(op, cid)
      _ <- permitted(op, "ReadChannel", p, cid)
      found <- EitherT.liftF(ids.traverse(id => pool.use(_.runOption(LookupRepo.message, id))))
      json <-
        EitherT.liftF(found.flatten.filter(_.cid == cid).traverse(messageJson(_, userOnly(p))))
    yield Json.obj("messages" -> json.asJson)

  private def updateMessage(p: StreamPrincipal, body: ACursor, id: String): Result =
    val op = "UpdateMessage"
    val msg = body.downField("message")
    for
      current <- message(op, id)
      _ <- mayModify(op, "UpdateMessage", p, current)
      updated <- EitherT.fromOptionF(
        messages.edit(
          current.cid,
          id,
          Some(msg.downField("text").as[String].toOption.getOrElse("")),
          Some(msg.downField("custom").focus.filter(_.isObject).getOrElse(Json.obj())),
          Some(msg.downField("attachments").focus.filter(_.isArray).getOrElse(Json.arr())),
        ),
        StreamApiError.notFound(op, s"message with id $id doesn't exist"),
      )
      json <- EitherT.liftF(messageJson(updated, userOnly(p)))
    yield Json.obj("message" -> json)

  private val builtInMessageFields =
    Set("text", "attachments", "pinned", "pin_expires", "silent", "mml")

  private def updateMessagePartial(p: StreamPrincipal, body: ACursor, id: String): Result =
    val op = "UpdateMessagePartial"
    val set = body.downField("set").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
    val unset = body.downField("unset").as[List[String]].toOption.getOrElse(Nil)
    for
      current <- message(op, id)
      _ <- mayModify(op, "UpdateMessage", p, current)
      custom = current.custom.asObject.getOrElse(JsonObject.empty)
      withSet = set.filterKeys(k => !builtInMessageFields(k)).toList.foldLeft(custom)((o, kv) =>
        o.add(kv._1, kv._2)
      )
      merged = unset.foldLeft(withSet)(_.remove(_))
      text = set("text").flatMap(_.asString).orElse(Option.when(unset.contains("text"))(""))
      attachments = set("attachments").filter(_.isArray)
        .orElse(Option.when(unset.contains("attachments"))(Json.arr()))
      updated <- EitherT.fromOptionF(
        messages.edit(current.cid, id, text, Some(Json.fromJsonObject(merged)), attachments),
        StreamApiError.notFound(op, s"message with id $id doesn't exist"),
      )
      json <- EitherT.liftF(messageJson(updated, userOnly(p)))
    yield Json.obj("message" -> json)

  private def deleteMessage(p: StreamPrincipal, id: String): Result =
    val op = "DeleteMessage"
    for
      current <- message(op, id)
      _ <- mayModify(op, "DeleteMessage", p, current)
      _ <- EitherT.liftF(messages.delete(current.cid, id).void)
      deleted <- message(op, id)
      json <- EitherT.liftF(messageJson(deleted, userOnly(p)))
    yield Json.obj("message" -> json)

  private def replies(req: Request[IO], p: StreamPrincipal, parentId: String): Result =
    val op = "GetReplies"
    val limit = req.params.get("limit").flatMap(_.toIntOption).getOrElse(DefaultMessageLimit)
      .max(1).min(MessagePaging.MaxLimit)
    for
      parent <- message(op, parentId)
      _ <- permitted(op, "ReadChannel", p, parent.cid)
      rows <- EitherT.liftF(pool.use(_.runList(StreamStateRepo.replies, (parentId, limit))))
      json <- EitherT.liftF(rows.reverse.traverse(messageJson(_, userOnly(p))))
    yield Json.obj("messages" -> json.asJson)

  // --- reactions ---

  private def reactionResult(m: Message, userId: String, reactionType: String): IO[Json] =
    for
      json <- messageJson(m, Some(userId))
      at <- IO.realTimeInstant.map(_.atOffset(java.time.ZoneOffset.UTC))
      reactor <- pool.use(s => state.users(s, List(userId)))
    yield Json.obj(
      "message" -> json,
      "reaction" ->
        V2Json.reaction(com.firemoot.db.ReactionRow(m.id, userId, reactionType, at), reactor),
    )

  private def sendReaction(p: StreamPrincipal, body: ACursor, id: String): Result =
    val op = "SendReaction"
    val reaction = body.downField("reaction")
    for
      reactionType <- EitherT.fromOption[IO](
        str(reaction, "type"),
        StreamApiError.input(op, "reaction.type is a required field"),
      )
      userId <- actor(op, p, str(reaction, "user_id"), str(reaction.downField("user"), "id"))
      m <- message(op, id)
      _ <- permitted(op, "CreateReaction", p, m.cid)
      _ <- EitherT.liftF(
        if bool(body, "enforce_unique").contains(true) then
          pool.use(_.runList(StreamStateRepo.ownReactions, (Arr(id), userId))).flatMap(
            _.filter(_.`type` != reactionType).traverse_(r =>
              reactions.remove(m.cid, id, userId, r.`type`)
            )
          )
        else IO.unit
      )
      _ <- EitherT.liftF(reactions.add(m.cid, id, userId, reactionType))
      json <- EitherT.liftF(reactionResult(m, userId, reactionType))
    yield json

  private def deleteReaction(
      req: Request[IO],
      p: StreamPrincipal,
      id: String,
      reactionType: String,
  ): Result =
    val op = "DeleteReaction"
    for
      userId <- actor(op, p, req.params.get("user_id"))
      m <- message(op, id)
      _ <- permitted(op, "DeleteReaction", p, m.cid)
      _ <- EitherT.liftF(reactions.remove(m.cid, id, userId, reactionType))
      json <- EitherT.liftF(reactionResult(m, userId, reactionType))
    yield json

  private def listReactions(req: Request[IO], p: StreamPrincipal, id: String): Result =
    val op = "GetReactions"
    val limit = req.params.get("limit").flatMap(_.toIntOption).getOrElse(10).max(1).min(300)
    for
      m <- message(op, id)
      _ <- permitted(op, "ReadChannel", p, m.cid)
      rows <- EitherT.liftF(pool.use(_.runList(StreamStateRepo.latestReactions, (Arr(id), limit))))
      us <- EitherT.liftF(pool.use(s => state.users(s, rows.map(_.userId))))
    yield Json.obj("reactions" -> rows.map(V2Json.reaction(_, us)).asJson)

  // --- search ---

  private def search(req: Request[IO], p: StreamPrincipal): Result =
    val op = "Search"
    val payload =
      req.params.get(
        "payload"
      ).flatMap(io.circe.parser.parse(_).toOption).getOrElse(Json.obj()).hcursor
    val limit = payload.downField("limit").as[Int].toOption.getOrElse(20).max(1).min(100)
    val text = payload.downField("query").as[String].toOption.orElse(
      payload.downField("message_filter_conditions").downField("text").focus.flatMap(t =>
        t.asString
          .orElse(t.hcursor.get[String]("$autocomplete").toOption)
          .orElse(t.hcursor.get[String]("$q").toOption)
      )
    )
    for
      q <- EitherT.fromOption[IO](
        text.filter(_.nonEmpty),
        StreamApiError.input(op, "either query or message_filter_conditions.text must be set"),
      )
      filter <- EitherT.fromEither[IO](StreamFilters.channelQuery(
        payload.downField("filter_conditions").focus,
        op,
      ))
      cid = filter.cids.flatMap(_.headOption)
      page <- EitherT.liftF(userOnly(p) match
        case Some(u) => query.searchAsMember(SearchRequest(q, cid, Some(limit)), u)
        case None => query.search(SearchRequest(q, cid, Some(limit))))
      json <- EitherT.liftF(page.hits.traverse(h => messageJson(h.message, userOnly(p))))
    yield Json.obj("results" -> json.map(m => Json.obj("message" -> m)).asJson)

  // --- uploads ---

  final private case class UploadPart(
      name: Option[String],
      filename: Option[String],
      mime: String,
      bytes: Array[Byte],
  )

  private def multipartParts(req: Request[IO]): IO[Option[List[UploadPart]]] =
    EntityDecoder.mixedMultipartResource[IO]().use(decoder =>
      decoder.decode(req, strict = false).value.flatMap {
        case Left(_) => IO.pure(None)
        case Right(multipart) =>
          multipart.parts.toList.traverse { part =>
            val mime = part.headers.get[org.http4s.headers.`Content-Type`]
              .map(ct => s"${ct.mediaType.mainType}/${ct.mediaType.subType}")
              .getOrElse("application/octet-stream")
            part.body.compile.to(Array).map(UploadPart(part.name, part.filename, mime, _))
          }.map(Some(_))
      }
    )

  private def upload(req: Request[IO], cid: Option[String]): IO[Response[IO]] =
    val op = "UploadFile"
    (for
      principal <- EitherT(auth.authenticate(req))
      store <- EitherT.fromOption[IO](
        uploads,
        StreamApiError(501, 19, s"$op failed with error: \"file uploads are not configured\""),
      )
      parts <- EitherT.fromOptionF(
        multipartParts(req),
        StreamApiError.input(op, "request is not multipart/form-data"),
      )
      file <- EitherT.fromOption[IO](
        parts.find(_.name.contains("file")),
        StreamApiError.input(op, "file is a required field"),
      )
      userField = parts.find(_.name.contains("user")).flatMap(u =>
        io.circe.parser.parse(new String(u.bytes, "UTF-8")).toOption
      )
      userId <- actor(op, principal, userField.flatMap(_.hcursor.get[String]("id").toOption))
      _ <- cid.fold(EitherT.rightT[IO, StreamApiError](())) { c =>
        existing(op, c) >> permitted(op, "UploadAttachment", principal, c).void
      }
      decision <- EitherT.liftF(principal match
        case StreamPrincipal.Server => rate.serverUpload(userId)
        case StreamPrincipal.User(_) => rate.upload(userId))
      _ <- limited(op, decision)
      url <- EitherT(store.store(
        Some(userId),
        file.filename.getOrElse("file"),
        file.mime,
        file.bytes,
      ).map(_.leftMap {
        case UploadError.UnsupportedType(m) =>
          StreamApiError.input(op, s"file type $m is not allowed")
        case UploadError.TooLarge(max) =>
          StreamApiError(
            413,
            22,
            s"$op failed with error: \"file is larger than the $max byte limit\"",
          )
      }))
    yield Json.obj("file" -> url.asJson, "duration" -> "0.00ms".asJson)).value.map {
      case Left(error) => error.response
      case Right(json) => Response[IO](Status.Created).withEntity(json)
    }

  // --- moderation ---

  private def flag(p: StreamPrincipal, body: ACursor): Result =
    val op = "Flag"
    for
      entityType <- EitherT.fromOption[IO](
        str(body, "entity_type"),
        StreamApiError.input(op, "entity_type is a required field"),
      )
      _ <- EitherT.cond[IO](
        entityType == "stream:chat:v1:message",
        (),
        StreamApiError.input(op, s"flagging $entityType is not supported"),
      )
      messageId <- EitherT.fromOption[IO](
        str(body, "entity_id"),
        StreamApiError.input(op, "entity_id is a required field"),
      )
      userId <- actor(op, p, str(body, "user_id"), str(body.downField("user"), "id"))
      m <- message(op, messageId)
      _ <- permitted(op, "FlagMessage", p, m.cid)
      created <- EitherT.fromOptionF(
        moderation.flag(m.cid, messageId, userId, str(body, "reason")),
        StreamApiError.notFound(op, s"message with id $messageId doesn't exist"),
      )
    yield Json.obj("item_id" -> created.id.toString.asJson)

  // --- server-side batch operations ---

  /** A finished batch operation, as `GET /api/v2/tasks/{id}` reports it. */
  final private case class StreamTask(id: String, at: java.time.OffsetDateTime, result: Json)

  /**
   * Stream runs batch deletes as async tasks; Firemoot completes them before
   * answering, so every task is already `completed` when its id is returned.
   */
  private val tasks = cats.effect.Ref.unsafe[IO, Map[String, StreamTask]](Map.empty)

  private def serverOnly(
      op: String,
      p: StreamPrincipal,
      action: String,
  ): EitherT[IO, StreamApiError, Unit] =
    p match
      case StreamPrincipal.Server => EitherT.rightT(())
      case StreamPrincipal.User(id) =>
        fail(StreamApiError.notAllowed(
          op,
          s"User '$id' with role 'user' is not allowed to perform action $action",
        ))

  private def recordTask(result: Json): IO[String] =
    for
      id <- com.firemoot.domain.UuidV7.next.map(_.toString)
      at <- IO.realTimeInstant.map(_.atOffset(java.time.ZoneOffset.UTC))
      _ <- tasks.update(_ + (id -> StreamTask(id, at, result)))
    yield id

  private def deleteUsers(p: StreamPrincipal, body: ACursor): Result =
    val op = "DeleteUsers"
    val ids = body.downField("user_ids").as[List[String]].toOption.getOrElse(Nil).distinct
    for
      _ <- serverOnly(op, p, "DeleteUser")
      _ <-
        EitherT.cond[IO](ids.nonEmpty, (), StreamApiError.input(op, "user_ids is a required field"))
      deleted <- EitherT.liftF(ids.traverse(id => users.delete(id).map(id -> _)))
      taskId <- EitherT.liftF(recordTask(Json.obj(
        "deleted" -> deleted.collect { case (id, true) => id }.asJson,
        "missing" -> deleted.collect { case (id, false) => id }.asJson,
      )))
    yield Json.obj("task_id" -> taskId.asJson)

  private def deleteChannels(p: StreamPrincipal, body: ACursor): Result =
    val op = "DeleteChannels"
    val cids = body.downField("cids").as[List[String]].toOption.getOrElse(Nil).distinct
    for
      _ <- serverOnly(op, p, "DeleteChannel")
      _ <- EitherT.cond[IO](cids.nonEmpty, (), StreamApiError.input(op, "cids is a required field"))
      outcomes <- EitherT.liftF(cids.traverse(cid => channels.softDelete(cid).map(cid -> _)))
      result = Json.fromFields(outcomes.map { (cid, ok) =>
        cid ->
          (if ok then Json.obj("status" -> "ok".asJson)
           else
             Json.obj("status" -> "error".asJson, "error" -> s"channel $cid doesn't exist".asJson))
      })
      taskId <- EitherT.liftF(recordTask(result))
    yield Json.obj("task_id" -> taskId.asJson, "result" -> result)

  private def getTask(id: String): Result =
    EitherT.fromOptionF(
      tasks.get.map(_.get(id)),
      StreamApiError.notFound("GetTask", s"task $id doesn't exist"),
    )
      .map { t =>
        Json.obj(
          "task_id" -> t.id.asJson,
          "status" -> "completed".asJson,
          "created_at" -> V2Json.ns(t.at),
          "updated_at" -> V2Json.ns(t.at),
          "result" -> t.result,
        )
      }

  /** The app settings the SDKs read (upload rules, chiefly). */
  private def appSettings: Json =
    val (mimes, imageCap, fileCap) = uploads.fold((Set.empty[String], 0L, 0L))(_.limits)
    def uploadConfig(prefix: Option[String], cap: Long) =
      Json.obj(
        "allowed_mime_types" -> mimes.filter(m => prefix.forall(m.startsWith)).toList.sorted.asJson,
        "allowed_file_extensions" -> Json.arr(),
        "blocked_mime_types" -> Json.arr(),
        "blocked_file_extensions" -> Json.arr(),
        "size_limit" -> cap.asJson,
      )
    Json.obj(
      "app" -> Json.obj(
        "name" -> "firemoot".asJson,
        "file_upload_config" -> uploadConfig(None, fileCap),
        "image_upload_config" -> uploadConfig(Some("image/"), imageCap),
        "async_url_enrich_enabled" -> false.asJson,
        "auto_translation_enabled" -> false.asJson,
        "disable_auth_checks" -> false.asJson,
        "disable_permissions_checks" -> false.asJson,
        "multi_tenant_enabled" -> false.asJson,
        "permission_version" -> "v2".asJson,
        "policies" -> Json.obj(),
        "push_notifications" -> Json.obj("version" -> "v3".asJson, "providers" -> Json.arr()),
        "call_types" -> Json.obj(),
        "channel_configs" -> Json.obj("messaging" -> V2Json.messagingConfig),
        "grants" -> Json.obj(),
      )
    )
