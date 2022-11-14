package controllers

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.mvc.*
import scala.util.chaining.*

import lila.api.GameApiV2
import lila.app.{ given, * }
import lila.common.config.MaxPerSecond
import lila.common.HTTPRequest
import lila.game.{ Game as GameModel }

final class Game(
    env: Env,
    apiC: => Api
) extends LilaController(env):

  def bookmark(gameId: String) =
    Auth { implicit ctx => me =>
      env.bookmark.api.toggle(gameId, me.id)
    }

  def delete(gameId: String) =
    Auth { implicit ctx => me =>
      OptionFuResult(env.game.gameRepo game gameId) { game =>
        if (game.pgnImport.flatMap(_.user) ?? (me.id.==))
          env.hub.bookmark ! lila.hub.actorApi.bookmark.Remove(game.id)
          (env.game.gameRepo remove game.id) >>
            (env.analyse.analysisRepo remove game.id) >>
            env.game.cached.clearNbImportedByCache(me.id) inject
            Redirect(routes.User.show(me.username))
        else
          fuccess {
            Redirect(routes.Round.watcher(game.id, game.naturalOrientation.name))
          }
      }
    }

  def exportOne(id: String) = Action.async { exportGame(GameModel takeGameId id, _) }

  private[controllers] def exportGame(gameId: GameModel.ID, req: RequestHeader): Fu[Result] =
    env.round.proxyRepo.gameIfPresent(gameId) orElse env.game.gameRepo.game(gameId) flatMap {
      case None => NotFound.toFuccess
      case Some(game) =>
        val config = GameApiV2.OneConfig(
          format = if (HTTPRequest acceptsJson req) GameApiV2.Format.JSON else GameApiV2.Format.PGN,
          imported = getBool("imported", req),
          flags = requestPgnFlags(req, extended = true),
          playerFile = get("players", req)
        )
        env.api.gameApiV2.exportOne(game, config) flatMap { content =>
          env.api.gameApiV2.filename(game, config.format) map { filename =>
            Ok(content)
              .pipe(asAttachment(filename))
              .withHeaders(
                lila.app.http.ResponseHeaders.headersForApiOrApp(req) *
              ) as gameContentType(config)
          }
        }
    }

  def exportByUser(username: String) =
    OpenOrScoped()(
      open = ctx => handleExport(username, ctx.me, ctx.req, oauth = false),
      scoped = req => me => handleExport(username, me.some, req, oauth = true)
    )

  def apiExportByUser(username: String) =
    AnonOrScoped() { req => me => handleExport(username, me, req, oauth = me.isDefined) }

  private def handleExport(username: String, me: Option[lila.user.User], req: RequestHeader, oauth: Boolean) =
    env.user.repo named username flatMap {
      _.filter(u => u.enabled || me.exists(_ is u) || me.??(isGranted(_.GamesModView, _))) ?? { user =>
        val format = GameApiV2.Format byRequest req
        WithVs(req) { vs =>
          val finished = getBoolOpt("finished", req) | true
          val config = GameApiV2.ByUserConfig(
            user = user,
            format = format,
            vs = vs,
            since = getTimestamp("since", req),
            until = getTimestamp("until", req),
            max = getInt("max", req) map (_ atLeast 1),
            rated = getBoolOpt("rated", req),
            perfType = (~get("perfType", req) split "," flatMap { lila.rating.PerfType(_) }).toSet,
            color = get("color", req) flatMap chess.Color.fromName,
            analysed = getBoolOpt("analysed", req),
            flags = requestPgnFlags(req, extended = false),
            sort = if (get("sort", req) has "dateAsc") GameApiV2.DateAsc else GameApiV2.DateDesc,
            perSecond = MaxPerSecond(me match {
              case Some(m) if m.id == "openingexplorer" => env.apiExplorerGamesPerSecond.get()
              case Some(m) if m is user.id              => 60
              case Some(_) if oauth                     => 30 // bonus for oauth logged in only (not for CSRF)
              case _                                    => 20
            }),
            playerFile = get("players", req),
            ongoing = getBool("ongoing", req) || !finished,
            finished = finished
          )
          if (me.exists(_.id == "openingexplorer"))
            Ok.chunked(env.api.gameApiV2.exportByUser(config))
              .pipe(noProxyBuffer)
              .as(gameContentType(config))
              .toFuccess
          else
            apiC
              .GlobalConcurrencyLimitPerIpAndUserOption(req, me)(env.api.gameApiV2.exportByUser(config)) {
                source =>
                  Ok.chunked(source)
                    .pipe(
                      asAttachmentStream(
                        s"lichess_${user.username}_${fileDate}.${format.toString.toLowerCase}"
                      )
                    )
                    .as(gameContentType(config))
              }
              .toFuccess

        }
      }
    }

  private def fileDate = DateTimeFormat forPattern "yyyy-MM-dd" print DateTime.now

  def apiExportByUserImportedGames(username: String) =
    AuthOrScoped()(
      auth = ctx => me => handleExportByUserImportedGames(username, me, ctx.req),
      scoped = req => me => handleExportByUserImportedGames(username, me, req)
    )

  private def handleExportByUserImportedGames(username: String, me: lila.user.User, req: RequestHeader) =
    fuccess {
      if (!me.is(username)) Forbidden("Imported games of other players cannot be downloaded")
      else
        apiC
          .GlobalConcurrencyLimitPerIpAndUserOption(req, me.some)(
            env.api.gameApiV2.exportUserImportedGames(me)
          ) { source =>
            Ok.chunked(source)
              .pipe(asAttachmentStream(s"lichess_${me.username}_$fileDate.imported.pgn"))
              .as(pgnContentType)
          }
    }

  def exportByIds =
    Action.async(parse.tolerantText) { req =>
      val config = GameApiV2.ByIdsConfig(
        ids = req.body.split(',').view.take(300).toSeq,
        format = GameApiV2.Format byRequest req,
        flags = requestPgnFlags(req, extended = false),
        perSecond = MaxPerSecond(30),
        playerFile = get("players", req)
      )
      apiC
        .GlobalConcurrencyLimitPerIP(HTTPRequest ipAddress req)(
          env.api.gameApiV2.exportByIds(config)
        ) { source =>
          noProxyBuffer(Ok.chunked(source)).as(gameContentType(config))
        }
        .toFuccess
    }

  private def WithVs(req: RequestHeader)(f: Option[lila.user.User] => Fu[Result]): Fu[Result] =
    get("vs", req) match
      case None => f(none)
      case Some(name) =>
        env.user.repo named name flatMap {
          case None       => notFoundJson(s"No such opponent: $name")
          case Some(user) => f(user.some)
        }

  private[controllers] def requestPgnFlags(req: RequestHeader, extended: Boolean) =
    lila.game.PgnDump.WithFlags(
      moves = getBoolOpt("moves", req) | true,
      tags = getBoolOpt("tags", req) | true,
      clocks = getBoolOpt("clocks", req) | extended,
      evals = getBoolOpt("evals", req) | extended,
      opening = getBoolOpt("opening", req) | extended,
      literate = getBoolOpt("literate", req) | false,
      pgnInJson = getBoolOpt("pgnInJson", req) | false,
      delayMoves = delayMovesFromReq(req)
    )

  private[controllers] def delayMovesFromReq(req: RequestHeader) =
    !get("key", req).exists(env.noDelaySecretSetting.get().value.contains)

  private[controllers] def gameContentType(config: GameApiV2.Config) =
    config.format match
      case GameApiV2.Format.PGN => pgnContentType
      case GameApiV2.Format.JSON =>
        config match
          case _: GameApiV2.OneConfig => JSON
          case _                      => ndJsonContentType

  private[controllers] def preloadUsers(game: GameModel): Funit =
    env.user.lightUserApi preloadMany game.userIds
