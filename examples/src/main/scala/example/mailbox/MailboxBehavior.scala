package example.mailbox

import com.devsisters.shardcake.UpickleSerialization._
import com.devsisters.shardcake.{ EntityType, Replier, Sharding }
import dev.profunktor.redis4cats.RedisCommands
import upickle.default._
import zio.{ Dequeue, RIO, Task, ZIO }

object MailboxBehavior {
  case class Letter(id: String, from: String, content: String, isRead: Boolean)
  object Letter {
    implicit val rw: ReadWriter[Letter] = macroRW
  }

  implicit def replierRW[A]: ReadWriter[Replier[A]] = readwriter[String].bimap[Replier[A]](
    replier => replier.id,
    id => Replier(id)
  )

  sealed trait MailboxMessage

  object MailboxMessage {
    case class SendLetter(from: String, content: String, replier: Replier[Option[Letter]]) extends MailboxMessage
    case class GetLetters(replier: Replier[List[Letter]])                                  extends MailboxMessage
    case class ReadLetter(letterId: String, replier: Replier[Option[Letter]])              extends MailboxMessage
    case class DeleteLetter(letterId: String, replier: Replier[Boolean])                   extends MailboxMessage
    case object Terminate                                                                  extends MailboxMessage

    implicit val sendLetterRW: ReadWriter[SendLetter]     = macroRW
    implicit val getLettersRW: ReadWriter[GetLetters]     = macroRW
    implicit val readLetterRW: ReadWriter[ReadLetter]     = macroRW
    implicit val deleteLetterRW: ReadWriter[DeleteLetter] = macroRW
    implicit val terminateRW: ReadWriter[Terminate.type]  = macroRW
    implicit val rw: ReadWriter[MailboxMessage]           = macroRW
  }

  object Mailbox extends EntityType[MailboxMessage]("mailbox")

  def behavior(
    entityId: String,
    messages: Dequeue[MailboxMessage]
  ): RIO[Sharding with RedisCommands[Task, String, String], Nothing] =
    ZIO.serviceWithZIO[RedisCommands[Task, String, String]](redis =>
      ZIO.logInfo(s"Started mailbox entity $entityId") *>
        messages.take.flatMap(handleMessage(entityId, redis, _)).forever
    )

  def handleMessage(
    entityId: String,
    redis: RedisCommands[Task, String, String],
    message: MailboxMessage
  ): RIO[Sharding, Unit] =
    message match {
      case MailboxMessage.SendLetter(from, content, replier) =>
        val letterKey = s"mailbox:$entityId:letters"
        val letterId  = java.util.UUID.randomUUID().toString
        val letter    = Letter(letterId, from, content, isRead = false)

        redis.lPush(letterKey, write(letter)) *>
          replier.reply(Some(letter))

      case MailboxMessage.GetLetters(replier) =>
        val letterKey = s"mailbox:$entityId:letters"

        redis
          .lRange(letterKey, 0, -1)
          .map(_.map(json => read[Letter](json)).toList)
          .flatMap(letters => replier.reply(letters))

      case MailboxMessage.ReadLetter(letterId, replier) =>
        val letterKey = s"mailbox:$entityId:letters"

        redis
          .lRange(letterKey, 0, -1)
          .map(_.map(json => read[Letter](json)).find(_.id == letterId))
          .flatMap(letterOpt => replier.reply(letterOpt))

      case MailboxMessage.DeleteLetter(letterId, replier) =>
        val letterKey = s"mailbox:$entityId:letters"

        for {
          allLetters <- redis.lRange(letterKey, 0, -1).map(_.map(json => read[Letter](json)))
          letterOpt   = allLetters.find(_.id == letterId)
          result     <- letterOpt match {
                          case Some(letter) =>
                            redis.lRem(letterKey, 1, write(letter)).as(true)
                          case None         =>
                            ZIO.succeed(false)
                        }
          _          <- replier.reply(result)
        } yield ()

      case MailboxMessage.Terminate =>
        ZIO.interrupt
    }
}
