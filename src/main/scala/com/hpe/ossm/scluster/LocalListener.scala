package com.hpe.ossm.scluster

import akka.actor.{Actor, Address, Timers}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberRemoved, MemberUp, UnreachableMember}
import akka.cluster.Cluster
import akka.pattern.Patterns.ask
import com.hpe.ossm.jcluster.messages.{Ping, Pong, UpdateClusterStatus}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class LocalListener extends Actor with Timers {
    private val cluster = Cluster(context.system)
    private var connectedSeedNodes = -1

    implicit val ec: ExecutionContext = context.dispatcher

    override def preStart(): Unit = {
        cluster.registerOnMemberUp(() ->
            cluster.subscribe(self, classOf[MemberUp], classOf[UnreachableMember], classOf[MemberRemoved])
        )
        timers.startPeriodicTimer(
            "updateStatus", new UpdateClusterStatus, 60.seconds)
    }

    override def postStop(): Unit = cluster.unsubscribe(self)

    private def reduceConnection(): Unit = {
        connectedSeedNodes = connectedSeedNodes - 1
        if (connectedSeedNodes <= 0) {
            println("All Seed Nodes are down, shutdown the node.")
            cluster.down(cluster.selfAddress)
        }
    }

    private def checkConnectionWithMaster(address: Address): Unit = {
        try {
            ask(context.actorSelection(address + "/user/localListener"), new Ping(), 5.seconds).mapTo[Pong]
                .onComplete(r =>
                    if (r.isFailure) {
                        println(s"Seed Node $address is down.")
                        reduceConnection()
                    }
                )
        } catch {
            case _: Exception =>
                println(s"Seed Node $address is down.")
                reduceConnection()
        }
    }

    private def checkConnections(status: CurrentClusterState): Unit = {
        val seedNodeAddress = cluster.settings.SeedNodes.filter(status.members.map(_.address).contains)
        val unReachSeedNodesAddress = seedNodeAddress.filter(status.unreachable.map(_.address).contains)
        if (seedNodeAddress.length == unReachSeedNodesAddress.length) //all seed node are unreached
            seedNodeAddress.foreach(checkConnectionWithMaster)
    }

    override def receive: Receive = {
        case s: CurrentClusterState => checkConnections(s)
        case m: MemberUp => println(s"Member is up  ${m.member.address}")
        case m: UnreachableMember => println(s"Member is unreachable  ${m.member.address}")
        case m: MemberRemoved => println(s"Member is removed  ${m.member.address}")
        case _: Ping => sender ! new Pong
        case _: UpdateClusterStatus => cluster.sendCurrentClusterState(self)
        case a: Any => println(s"Received ignored message ${a.toString}")
    }
}