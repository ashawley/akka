/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.camel.service

import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch

import org.apache.camel.builder.RouteBuilder

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.annotation.consume
import se.scalablesolutions.akka.camel.{Consumer, CamelContextManager}
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.camel.component.ActiveObjectComponent
import collection.mutable.ListBuffer

/**
 * Actor that publishes consumer actors as Camel endpoints at the CamelContext managed
 * by se.scalablesolutions.akka.camel.CamelContextManager. It accepts messages of type
 * se.scalablesolutions.akka.camel.service.ConsumerRegistered and
 * se.scalablesolutions.akka.camel.service.ConsumerUnregistered.
 *
 * @author Martin Krasser
 */
class ConsumerPublisher extends Actor with Logging {

  // TODO: redesign waiting (actor testing) mechanism
  //       - do not use custom methods on actor
  //       - only interact by passing messages
  // TODO: factor out code that is needed for testing

  @volatile private var publishActorLatch = new CountDownLatch(0)
  @volatile private var unpublishActorLatch = new CountDownLatch(0)

  /**
   *  Adds a route to the actor identified by a Publish message to the global CamelContext.
   */
  protected def receive = {
    case r: ConsumerRegistered => {
      handleConsumerRegistered(r)
      publishActorLatch.countDown // needed for testing only.
    }
    case u: ConsumerUnregistered => {
      handleConsumerUnregistered(u)
      unpublishActorLatch.countDown // needed for testing only.
    }
    case d: ConsumerMethodRegistered => {
      handleConsumerMethodDetected(d)
    }
    case _ => { /* ignore */}
  }

  /**
   * Sets the expected number of actors to be published. Used for testing only.
   */
  private[camel] def expectPublishActorCount(count: Int): Unit =
    publishActorLatch = new CountDownLatch(count)

  /**
   * Sets the expected number of actors to be unpublished. Used for testing only.
   */
  private[camel] def expectUnpublishActorCount(count: Int): Unit =
    unpublishActorLatch = new CountDownLatch(count)

  /**
   * Waits for the expected number of actors to be published. Used for testing only.
   */
  private[camel] def awaitPublishActor = publishActorLatch.await

  /**
   * Waits for the expected number of actors to be unpublished. Used for testing only.
   */
  private[camel] def awaitUnpublishActor = unpublishActorLatch.await

  /**
   * Creates a route to the registered consumer actor.
   */
  private def handleConsumerRegistered(event: ConsumerRegistered) {
    CamelContextManager.context.addRoutes(new ConsumerRoute(event.uri, event.id, event.uuid))
    log.info("published actor %s (%s) at endpoint %s" format (event.clazz, event.id, event.uri))
  }

  /**
   * Stops route to the already un-registered consumer actor.
   */
  private def handleConsumerUnregistered(event: ConsumerUnregistered) {
    CamelContextManager.context.stopRoute(event.id)
    log.info("unpublished actor %s (%s) from endpoint %s" format (event.clazz, event.id, event.uri))
  }

  private def handleConsumerMethodDetected(event: ConsumerMethodRegistered) {
    // using the actor uuid is highly experimental
    val objectId = event.init.actorRef.uuid
    val targetClass = event.init.target.getName
    val targetMethod = event.method.getName

    CamelContextManager.activeObjectRegistry.put(objectId, event.activeObject)
    CamelContextManager.context.addRoutes(new ConsumerMethodRoute(event.uri, objectId, targetMethod))
    log.info("published method %s.%s (%s) at endpoint %s" format (targetClass, targetMethod, objectId, event.uri))
  }
}

/**
 * Defines the route to a consumer actor.
 *
 * @param endpointUri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> refers to Actor.uuid, <code>false</code> if
 *             <code>id</code> refers to Actor.getId.
 *
 * @author Martin Krasser
 */
class ConsumerRoute(val endpointUri: String, id: String, uuid: Boolean) extends RouteBuilder {
  //
  //
  // TODO: factor out duplicated code from ConsumerRoute and ConsumerMethodRoute
  //
  //

  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    bodyConversions.get(schema) match {
      case Some(clazz) => from(endpointUri).routeId(id).convertBodyTo(clazz).to(actorUri)
      case None        => from(endpointUri).routeId(id).to(actorUri)
    }
  }

  private def actorUri = (if (uuid) "actor:uuid:%s" else "actor:id:%s") format id
}

class ConsumerMethodRoute(val endpointUri: String, id: String, method: String) extends RouteBuilder {
  //
  //
  // TODO: factor out duplicated code from ConsumerRoute and ConsumerMethodRoute
  //
  //
  
  // TODO: make conversions configurable
  private val bodyConversions = Map(
    "file" -> classOf[InputStream]
  )

  def configure = {
    val schema = endpointUri take endpointUri.indexOf(":") // e.g. "http" from "http://whatever/..."
    bodyConversions.get(schema) match {
      case Some(clazz) => from(endpointUri).convertBodyTo(clazz).to(activeObjectUri)
      case None        => from(endpointUri).to(activeObjectUri)
    }
  }

  private def activeObjectUri = "%s:%s?method=%s" format (ActiveObjectComponent.DEFAULT_SCHEMA, id, method)
}

/**
 * A registration listener that triggers publication and un-publication of consumer actors.
 *
 * @author Martin Krasser
 */
class PublishRequestor extends Actor {
  private val events = ListBuffer[ConsumerEvent]()
  private var publisher: Option[ActorRef] = None

  protected def receive = {
    case ActorRegistered(actor) =>
      for (event <- ConsumerRegistered.forConsumer(actor)) deliverCurrentEvent(event)
    case ActorUnregistered(actor) => 
      for (event <- ConsumerUnregistered.forConsumer(actor)) deliverCurrentEvent(event)
    case AspectInitRegistered(proxy, init) =>
      for (event <- ConsumerMethodRegistered.forConsumer(proxy, init)) deliverCurrentEvent(event)
    case PublishRequestorInit(pub) => {
      publisher = Some(pub)
      deliverBufferedEvents
    }
    case _ => { /* ignore */ }
  }

  private def deliverCurrentEvent(event: ConsumerEvent) = {
    publisher match {
      case Some(pub) => pub ! event
      case None      => events += event
    }
  }

  private def deliverBufferedEvents = {
    for (event <- events) deliverCurrentEvent(event)
    events.clear
  }
}

case class PublishRequestorInit(consumerPublisher: ActorRef)

/**
 * A consumer event.
 *
 * @author Martin Krasser
 */
sealed trait ConsumerEvent

/**
 * Event indicating that a consumer actor has been registered at the actor registry.
 *
 * @param clazz clazz name of the referenced actor
 * @param uri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> is the actor's uuid, <code>false</code> if
 *             <code>id</code> is the actor's id.
 *
 * @author Martin Krasser
 */
case class ConsumerRegistered(clazz: String, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

/**
 * Event indicating that a consumer actor has been unregistered from the actor registry.
 *
 * @param clazz clazz name of the referenced actor
 * @param uri endpoint URI of the consumer actor
 * @param id actor identifier
 * @param uuid <code>true</code> if <code>id</code> is the actor's uuid, <code>false</code> if
 *             <code>id</code> is the actor's id.
 *
 * @author Martin Krasser
 */
case class ConsumerUnregistered(clazz: String, uri: String, id: String, uuid: Boolean) extends ConsumerEvent

case class ConsumerMethodRegistered(activeObject: AnyRef, init: AspectInit, uri: String, method: Method) extends ConsumerEvent

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerRegistered {
  /**
   * Optionally creates an ConsumerRegistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerRegistered] = actorRef match {
    case ConsumerDescriptor(clazz, uri, id, uuid) => Some(ConsumerRegistered(clazz, uri, id, uuid))
    case _                                        => None
  }
}

/**
 * @author Martin Krasser
 */
private[camel] object ConsumerUnregistered {
  /**
   * Optionally creates an ConsumerUnregistered event message for a consumer actor or None if
   * <code>actorRef</code> is not a consumer actor.
   */
  def forConsumer(actorRef: ActorRef): Option[ConsumerUnregistered] = actorRef match {
    case ConsumerDescriptor(clazz, uri, id, uuid) => Some(ConsumerUnregistered(clazz, uri, id, uuid))
    case _                                        => None
  }
}

private[camel] object ConsumerMethodRegistered {
  def forConsumer(activeObject: AnyRef, init: AspectInit): List[ConsumerMethodRegistered] = {
    // TODO: support/test annotations on interface methods
    // TODO: support/test annotations on superclass methods
    // TODO: document that overloaded methods are not supported
    if (init.remoteAddress.isDefined) Nil // let remote node publish active object methods on endpoints
    else for (m <- activeObject.getClass.getMethods.toList; if (m.isAnnotationPresent(classOf[consume])))
    yield ConsumerMethodRegistered(activeObject, init, m.getAnnotation(classOf[consume]).value, m)
  }
}

/**
 * Describes a consumer actor with elements that are relevant for publishing an actor at a
 * Camel endpoint (or unpublishing an actor from an endpoint).
 *
 * @author Martin Krasser
 */
private[camel] object ConsumerDescriptor {

  /**
   * An extractor that optionally creates a 4-tuple from a consumer actor reference containing
   * the target actor's class name, endpoint URI, identifier and a hint whether the identifier
   * is the actor uuid or actor id. If <code>actorRef</code> doesn't reference a consumer actor,
   * None is returned.
   */
  def unapply(actorRef: ActorRef): Option[(String, String, String, Boolean)] =
    unapplyConsumerInstance(actorRef) orElse unapplyConsumeAnnotated(actorRef)

  private def unapplyConsumeAnnotated(actorRef: ActorRef): Option[(String, String, String, Boolean)] = {
    val annotation = actorRef.actorClass.getAnnotation(classOf[consume])
    if (annotation eq null) None
    else if (actorRef.remoteAddress.isDefined) None
    else Some((actorRef.actor.getClass.getName, annotation.value, actorRef.id, false))
  }

  private def unapplyConsumerInstance(actorRef: ActorRef): Option[(String, String, String, Boolean)] =
    if (!actorRef.actor.isInstanceOf[Consumer]) None
    else if (actorRef.remoteAddress.isDefined) None
    else Some((actorRef.actor.getClass.getName, actorRef.actor.asInstanceOf[Consumer].endpointUri, actorRef.uuid, true))
}
