// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.cluster

sealed trait PartitionRegionCommand

/**
 * A Passivate message is used to coordinate graceful shutdown of a shard's child actors with the shard.
 *
 * @param stopMessage The message sent back to the child to acknowledge the child's request to passivate.
 *                    Once the child receives this, it may terminate.  If it terminates before this, the parent
 *                    actors supervision strategy may kick in and restart the actor.
 */
case class Passivate(stopMessage: Any) extends PartitionRegionCommand