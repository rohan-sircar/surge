// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.cluster

import akka.actor.{ ActorContext, ActorSystem, Address }
import com.ultimatesoftware.scala.core.kafka.HostPort
import org.apache.kafka.streams.state.HostInfo

trait ActorSystemHostAwareness {
  def actorSystem: ActorSystem

  protected lazy val localAddress: Address = RemoteAddressExtension(actorSystem).address
  protected lazy val localHostname: String = localAddress.host.getOrElse("localhost")
  protected lazy val localPort: Int = localAddress.port.getOrElse(0)
  protected lazy val akkaProtocol: String = localAddress.protocol

  protected lazy val applicationHostPort: Option[String] = for {
    akkaHost ← localAddress.host
    akkaPort ← localAddress.port
  } yield {
    s"$akkaHost:$akkaPort"
  }

  protected def isHostPortThisNode(hostPort: HostPort): Boolean = {
    val hostPortsMatch = hostPort.host == localHostname && hostPort.port == localPort
    localAddress.hasLocalScope || hostPortsMatch
  }

  protected def isHostInfoThisNode(hostInfo: HostInfo): Boolean = {
    val hostPort = HostPort(hostInfo.host(), hostInfo.port())
    isHostPortThisNode(hostPort)
  }
}

trait ActorHostAwareness extends ActorSystemHostAwareness {
  implicit def context: ActorContext

  override def actorSystem: ActorSystem = context.system
}