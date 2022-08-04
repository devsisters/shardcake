package example.complex

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.{ EntityType, Sharding }
import dev.profunktor.redis4cats.RedisCommands
import zio.{ Dequeue, RIO, Task, ZIO }

import scala.util.{ Failure, Success, Try }

object GuildBehavior {
  sealed trait GuildMessage

  object GuildMessage {
    case class Join(userId: String, replier: Replier[Try[Set[String]]]) extends GuildMessage
    case class Leave(userId: String)                                    extends GuildMessage
  }

  object Guild extends EntityType[GuildMessage]("guild")

  def behavior(
    entityId: String,
    messages: Dequeue[GuildMessage]
  ): RIO[Sharding with RedisCommands[Task, String, String], Nothing] =
    ZIO.serviceWithZIO[RedisCommands[Task, String, String]](redis =>
      ZIO.logInfo(s"Started entity $entityId") *>
        messages.take.flatMap(handleMessage(entityId, redis, _)).forever
    )

  def handleMessage(
    entityId: String,
    redis: RedisCommands[Task, String, String],
    message: GuildMessage
  ): RIO[Sharding, Unit] =
    message match {
      case GuildMessage.Join(userId, replier) =>
        redis
          .lRange(entityId, 0, -1)
          .map(_.toSet)
          .flatMap(members =>
            if (members.size >= 5)
              replier.reply(Failure(new Exception("Guild is already full!")))
            else
              (redis.lPush(entityId, userId) *>
                replier.reply(Success(members + userId))).unless(members.contains(userId)).unit
          )
      case GuildMessage.Leave(userId)         =>
        redis.lRem(entityId, 1, userId).unit
    }
}
