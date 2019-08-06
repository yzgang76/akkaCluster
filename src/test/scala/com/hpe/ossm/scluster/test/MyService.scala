package com.hpe.ossm.scluster.test

import akka.actor.{ActorRef, DeadLetter, Props}
import com.hpe.ossm.jcluster.messages.ServiceStatusEvents
import com.hpe.ossm.scluster.{ClusterNode, ServiceEntryActor}
import akka.pattern.Patterns.ask
import com.hpe.ossm.scluster.management.DeadLetterListener
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class MyService extends ServiceEntryActor("MyService", mutable.HashMap("CM" -> List.empty[ActorRef])) {
    implicit val ec: ExecutionContext = context.dispatcher

    override def preStart(): Unit = {
        super.preStart()
        timers.startPeriodicTimer("service", "Hello", 5.seconds)
    }

    override def postStop(): Unit = super.postStop()

    override def receive: Receive = super.receive.orElse {
        case s: String if ServiceStatusEvents.STATUS_AVAILABLE.equals(status) =>
            val ref = getService("CM")
            if (ref != null) {
                val reply = ask(ref, s, 5.seconds).mapTo[String]
                reply.onComplete {
                    case Success(r) =>println("[MyService Reply] " + r)
                    case Failure(e)=>println(s"Failed to ask. ${e.getMessage}")
                }
            }
        case s: Any => println(s"Ignored message ${s.toString}")
    }
}

class MyServiceApp extends ClusterNode("MyService", null) {}

object MyServiceNode {
    def main(args: Array[String]): Unit = {
        val t = new MyServiceApp()
        val ref = t.system.actorOf(Props(classOf[DeadLetterListener]), "deadLetterListener")
        t.system.eventStream.subscribe(ref, classOf[DeadLetter])
        t.system.actorOf(Props(classOf[MyService]), "myService")
    }
}