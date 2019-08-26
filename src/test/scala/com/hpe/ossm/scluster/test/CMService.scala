package com.hpe.ossm.scluster.test

import akka.actor.{DeadLetter, Props}
import akka.cluster.Cluster
import com.hpe.ossm.scluster.management.DeadLetterListener
import com.hpe.ossm.scluster.{ClusterNode, ServiceEntryActor}

class CMService extends ServiceEntryActor("CM", null) {
    val cluster=Cluster(context.system)
    override def preStart(): Unit = {
        println(s"CMService starting")
        super.preStart()
    }

    override def postStop(): Unit = super.postStop()


    override def receive: Receive = {
        super.receive.orElse {
            case s: String =>
                println(s"[CM]received "+s)
                sender ! cluster.selfAddress + "|" + s
            case s: Any => println(s"Ignored message ${s.toString}")
        }
    }
}

class CMServiceApp extends ClusterNode("CMService", null)

object CMServiceNode {
    def main(args: Array[String]): Unit = {
        val t = new CMServiceApp
        val ref = t.system.actorOf(Props(classOf[DeadLetterListener]), "deadLetterListener")
        t.system.eventStream.subscribe(ref, classOf[DeadLetter])
        t.system.actorOf(Props(classOf[CMService]), "CM")
    }
}
