// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.cluster

import java.net.URLEncoder

import akka.pattern.pipe
import akka.pattern.ask
import akka.actor._
import akka.util.MessageBufferMap
import com.ultimatesoftware.kafka.streams.HealthCheck
import com.ultimatesoftware.kafka.streams.HealthyActor.GetHealth
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * A shard is a building block for scaling that is responsible for tracking & managing many child actors underneath.  The child
 * actors are what would actually run business logic, while the shard actor is responsible for lifecycle management of and
 * message routing for any created child actors.  There can be many shards across an application instance or instances, each
 * operating independently of one another.  Using a shard as a unit of scale allows for easily moving groups of actors to
 * different application nodes as a service scales up or down.
 *
 * When a child actor wants to stop cleanly, it must send a Passivate message to the shard.  This message includes a stop message
 * that is forwarded back to the child actor as an acknowledgement that it may begin shutting down.  When the shard receives a
 * Passivate message, it adds the sender of the message to the list of known children who are shutting down.  If new messages for
 * any child on this list are received, they are buffered (up to 1000 messages per child) until the child finishes shutting down.
 * Once the child actually terminates, if there are any buffered messages for the child, it will be immediately restarted and any messages
 * buffered will be forwarded to it in the order they were received by the shard.
 */
object Shard {
  sealed trait ShardMessage

  def props[IdType](shardId: String, regionLogicProvider: PerShardLogicProvider[IdType], extractEntityId: PartialFunction[Any, IdType]): Props = {
    Props(new Shard(shardId, regionLogicProvider, extractEntityId))
  }
}

class Shard[IdType](
    shardId: String,
    regionLogicProvider: PerShardLogicProvider[IdType],
    extractEntityId: PartialFunction[Any, IdType]) extends Actor {

  private val log: Logger = LoggerFactory.getLogger(getClass)
  private val bufferSize = 1000

  // Don't copy this pattern of mutability in scala. I am using it here for efficiency since
  // messages in this actor are processed one at a time and we don't use any external thread
  // contexts/asynchronous calls from this actor.
  private var idByRef = Map.empty[ActorRef, IdType]
  private var refById = Map.empty[IdType, ActorRef]
  private var passivating = Set.empty[ActorRef]
  private val messageBuffers = new MessageBufferMap[IdType]

  private val actorProvider = regionLogicProvider.actorProvider(context)

  override def receive: Receive = {
    case msg: Terminated                         ⇒ receiveTerminated(msg)
    case msg: Passivate                          ⇒ receivePassivate(msg)
    case GetHealth                               ⇒ getHealthCheck()
    case msg: HealthCheck                        ⇒ sender() ! msg
    case msg if extractEntityId.isDefinedAt(msg) ⇒ deliverMessage(msg, sender())
  }

  private def deliverMessage(msg: Any, send: ActorRef): Unit = {
    val id = extractEntityId(msg)
    if (id == null || id == "") {
      log.warn("Unsure of how to route message with class [{}], dropping it.", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      if (messageBuffers.contains(id)) {
        appendToMessageBuffer(id, msg, send)
      } else {
        deliverTo(id, msg, send)
      }
    }
  }

  private def deliverTo(id: IdType, payload: Any, snd: ActorRef): Unit = {
    getOrCreateEntity(id).tell(payload, snd)
  }

  private def getOrCreateEntity(id: IdType): ActorRef = {
    val name = URLEncoder.encode(id.toString, "utf-8")
    context.child(name) match {
      case Some(child) ⇒ child
      case None ⇒
        log.debug(s"Shard $shardId creating child actor for aggregate $name")
        val actorRef = context.watch(context.actorOf(actorProvider.actorPropsById(id), name))
        idByRef = idByRef.updated(actorRef, id)
        refById = refById.updated(id, actorRef)

        actorRef
    }
  }

  private def appendToMessageBuffer(id: IdType, msg: Any, snd: ActorRef): Unit = {
    if (messageBuffers.totalSize >= bufferSize) {
      log.debug("Buffer is full, dropping message for entity [{}]", id)
      context.system.deadLetters ! msg
    } else {
      log.debug("Message for entity [{}] buffered", id)
      messageBuffers.append(id, msg, snd)
    }
  }

  private def receiveTerminated(terminated: Terminated): Unit = {
    idByRef.get(terminated.actor) match {
      case Some(id) ⇒
        entityTerminated(id, terminated.actor)
      case None ⇒
        log.debug("Partition region saw untracked actor terminate {}", terminated.actor.path.toStringWithoutAddress)
    }
  }

  private def entityTerminated(id: IdType, ref: ActorRef): Unit = {
    idByRef -= ref
    refById -= id

    if (messageBuffers.getOrEmpty(id).nonEmpty) {
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      sendMsgBuffer(id)
    } else {
      log.trace("PartitionRegion saw tracked entity terminate for aggregate [{}]", id)
      messageBuffers.remove(id)
    }

    passivating = passivating - ref
  }

  private def sendMsgBuffer(entityId: IdType): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrEmpty(entityId)
    messageBuffers.remove(entityId)

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", entityId, messages.size)
      getOrCreateEntity(entityId)
      // Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages.foreach {
        case (msg, snd) ⇒ deliverMessage(msg, snd)
      }
    }
  }

  private def receivePassivate(passivate: Passivate): Unit = {
    val stopMessage = passivate.stopMessage
    val entity = sender()

    idByRef.get(entity) match {
      case Some(id) ⇒
        if (!messageBuffers.contains(id)) {
          passivating = passivating + entity
          messageBuffers.add(id)
          entity ! stopMessage
        } else {
          log.debug("Passivation already in progress for {}. Not sending stopMessage back to entity.", entity)
        }
      case None ⇒ log.debug("Unknown entity {}. Not sending stopMessage back to entity.", entity)
    }
  }

  private def getHealthCheck() = {
    implicit val executionContext = ExecutionContext.global
    Future.sequence(
      refById.map {
        case (id, ref) ⇒
          ref.ask(GetHealth)(100 millis).mapTo[HealthCheck]
            .recoverWith {
              case err: Throwable ⇒
                Future.successful(HealthCheck(
                  name = id.toString,
                  running = false,
                  message = Some(err.getMessage)))
            }(ExecutionContext.global)
      }).map { healthChecks ⇒
        HealthCheck(
          name = s"Shard-$shardId",
          running = true,
          components = Some(healthChecks.toSeq))
      }.pipeTo(self)(sender())
  }
}
