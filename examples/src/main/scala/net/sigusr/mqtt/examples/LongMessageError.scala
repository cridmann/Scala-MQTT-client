package net.sigusr.mqtt.examples

import akka.actor.{Props, ActorSystem}
import com.typesafe.config.ConfigFactory


object LongMessageError extends App {

  val configDebug =
    """akka {
         loglevel = DEBUG
         actor {
            debug {
              receive = on
              autoreceive = off
              lifecycle = off
            }
         }
       }
    """

  val system = ActorSystem("mqtt-app", ConfigFactory.parseString(configDebug))
  val subscriber = system.actorOf(Props(classOf[LocalSubscriber], Vector(localPublisher)))
  val publisher = system.actorOf(Props(classOf[LocalPublisher], Vector()))

  scala.io.StdIn.readLine(s"Hit ENTER to exit ...")
  system.shutdown()
}
