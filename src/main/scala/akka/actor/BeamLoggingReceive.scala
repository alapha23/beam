/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.actor.Actor.Receive
import akka.event.{LogSource, Logging, LoggingReceive}
import akka.event.Logging.{LogEvent, LogLevel}
import language.existentials

import scala.runtime.BoxedUnit

object BeamLoggingReceive {

  /**
    * Wrap a Receive partial function in a logging enclosure, which sends a
    * debug message to the event bus each time before a message is matched.
    * This includes messages which are not handled.
    *
    * <pre><code>
    * def receive = LoggingReceive {
    *   case x => ...
    * }
    * </code></pre>
    *
    * This method does NOT modify the given Receive unless
    * `akka.actor.debug.receive` is set in configuration.
    */
  def apply(r: Receive)(implicit context: ActorContext): Receive = withLabel(null)(r)

  /**
    * Wrap a Receive partial function in a logging enclosure, which sends a
    * message with given log level to the event bus each time before a message is matched.
    * This includes messages which are not handled.
    */
  def apply(logLevel: LogLevel)(r: Receive)(implicit context: ActorContext): Receive = withLabel(null, logLevel)(r)

  /**
    * Java API: compatible with lambda expressions
    */
  @deprecated("Use the create method with `AbstractActor.Receive` parameter instead.", since = "2.5.0")
  def create(r: Receive, context: ActorContext): Receive = apply(r)(context)

  /**
    * Java API: compatible with lambda expressions
    */
  def create(r: AbstractActor.Receive, context: AbstractActor.ActorContext): AbstractActor.Receive =
    new AbstractActor.Receive(
      apply(r.onMessage.asInstanceOf[PartialFunction[Any, Unit]])(context)
        .asInstanceOf[PartialFunction[Any, BoxedUnit]]
    )

  /**
    * Create a decorated logger which will append `" in state " + label` to each message it logs.
    */
  def withLabel(label: String, logLevel: LogLevel)(r: Receive)(implicit context: ActorContext): Receive = r match {
    case _: BeamLoggingReceive => r
    case _ =>
      if (context.system.settings.AddLoggingReceive) new BeamLoggingReceive(None, r, Option(label), logLevel) else r
  }

  /**
    * Create a decorated logger which will append `" in state " + label` to each message it logs.
    */
  def withLabel(label: String)(r: Receive)(implicit context: ActorContext): Receive =
    withLabel(label, Logging.DebugLevel)(r)
}

class BeamLoggingReceive(source: Option[AnyRef], r: Receive, label: Option[String], logLevel: LogLevel)(implicit
  context: ActorContext
) extends LoggingReceive(source, r, label, logLevel) {

  override def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    if (context.system.eventStream.logLevel >= logLevel) {
      val src = source.getOrElse(context.asInstanceOf[ActorCell].actor)
      val (str, clazz) = LogSource.fromAnyRef(src)
      val message = "###Actor### received " + (if (handled) "handled"
                                               else "unhandled") + " message " + o + " from " + context
        .sender() +
        (label match {
          case Some(l) => " in state " + l
          case _       => ""
        })
      val event = src match {
        case a: DiagnosticActorLogging => LogEvent(logLevel, str, clazz, message, a.log.mdc)
        case _                         => LogEvent(logLevel, str, clazz, message)
      }
      context.system.eventStream.publish(event)
    }
    handled
  }
}