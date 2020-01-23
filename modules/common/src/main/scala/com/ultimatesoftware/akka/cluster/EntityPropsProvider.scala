// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.cluster

import akka.actor.{ ActorContext, Props }

/**
 * A shard is an independently operating unit for scaling.  The shard acts as a parent to a cluster of business logic
 * implementing actors and potentially other per shard actors (ex. a shared producer for all business logic actors
 * within the shard). The PerShardLogicProvider is passed into a shard on creation and is used for creating anything
 * that should be run on each shard within a cluster of shards.
 *
 * @tparam IdType Aggregate ID type for the business logic actors of this shard.
 */
trait PerShardLogicProvider[IdType] {
  /**
   * This is run once per shard on shard startup.  It can be used for creating any entities that need to be run as part
   * of a shard and must return an EntityPropsProvider, which is used to create individual business logic entities on demand
   * as messages for a particular entity id are delivered.
   *
   * @param context The actor context for the shard that calls this function.  It can be used to create other actors
   *                which become children of the shard.
   * @return An EntityPropsProvider which is able to create business logic entities with a particular entity identifier.
   */
  def actorProvider(context: ActorContext): EntityPropsProvider[IdType]
}

/**
 * Used to create individual business logic entities with a unique identifier.  Messages routed to a shard for a particular id
 * will always be delivered to an actor whose Props are returned by this function.  This is used by a shard to create an actor
 * by a particular id when it does not already have a child business entity with the same id.
 *
 * @tparam IdType The id type for the business logic entity
 */
trait EntityPropsProvider[IdType] {
  def actorPropsById(actorId: IdType): Props
}