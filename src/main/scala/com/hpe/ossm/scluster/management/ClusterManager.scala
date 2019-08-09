package com.hpe.ossm.scluster.management

import java.time.Duration
import java.util

import akka.actor.{Actor, ActorRef, RootActorPath, Timers}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberDowned, MemberUp, UnreachableMember}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.{Cluster, Member}
import akka.pattern.ask
import akka.util.Timeout
import com.hpe.ossm.jcluster.messages.{LookingForService, Ping, Pong, ServiceStatusEvents}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import com.hpe.ossm.scala.lang.util.KafkaUtil
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer

import scala.collection.JavaConverters._

class ClusterManager extends Actor with Timers {

    case object CheckUnreached

    case object Consume

    private val cluster = Cluster(context.system)
    private val mediator = DistributedPubSub(context.system).mediator
    private implicit val ec: ExecutionContext = context.dispatcher
    private val topic = "ServiceEvents"
    private val conf = context.system.settings.config
    private val kafkaActive = conf.getBoolean("kafka.active")
    private var producer: KafkaProducer[String, java.io.Serializable] = _
    private var consumer: KafkaConsumer[String, java.io.Serializable] = _

    override def preStart: Unit = {
        println(s"Kafka is active : $kafkaActive")
        cluster.registerOnMemberUp(
            () -> {
                cluster.subscribe(self, classOf[MemberUp], classOf[MemberDowned], classOf[UnreachableMember])
                println(s"ClusterManager starting ${self.path}")
                if (kafkaActive) {
                    producer = KafkaUtil.createProcedure(conf)
                    consumer = KafkaUtil.createConsumer(conf, self.path.toSerializationFormatWithAddress(cluster.selfAddress))
                    consumer.subscribe(util.Arrays.asList(topic))
                    timers.startPeriodicTimer("consume", Consume, 100.millis)
                } else {
                    mediator ! Subscribe(topic, self)
                }
                timers.startPeriodicTimer("monitor", CheckUnreached, 60.seconds)
            }
        )
    }

    override def postStop: Unit = {
        if(kafkaActive){
            producer.close()
            consumer.close()
        }else{
            cluster.unsubscribe(self)
        }
        println(s"ClusterManager stopped")
    }

    private def checkUnreachableMember(m: Member): Unit = {
        implicit val timeout: Timeout = 5.seconds
        val path: Future[ActorRef] = context.actorSelection(RootActorPath(m.address) + "/user/localListener").resolveOne()
        path.onComplete(r => {
            if (r.isFailure) {
                cluster.down(m.address)
                upNodes = upNodes.filter(_ == m)
            } else {
                val res: Future[Pong] = (r.get ? new Ping).mapTo[Pong]
                res.onComplete(o =>
                    if (o.isFailure) {
                        cluster.down(m.address)
                        upNodes = upNodes.filter(_ == m)
                    }
                )
            }
        })
    }

    private var upNodes = Seq.empty[Member]

    private def replyPing(): Unit = {
        if (upNodes.find(node => node.address == sender.path.root.address).orNull != null) sender ! new Pong
        else println(s"received ping from outside node ${sender.path}")
    }

    override def receive: Receive = {
        case m: MemberUp if !upNodes.contains(m.member) =>
            println(s"Up ${m.member}")
            upNodes = upNodes :+ m.member
        case m: MemberDowned => println(s"Downed ${m.member}")
        case um: UnreachableMember => println(s"Unreached  ${um.member}")
            checkUnreachableMember(um.member)
        case CheckUnreached => cluster.sendCurrentClusterState(self)
        case stat: CurrentClusterState =>
            //            val notSeenBy = stat.members.map(_.address) -- stat.seenBy
            //            if (notSeenBy.nonEmpty) println(s"Not seenBy $notSeenBy")
            stat.unreachable.foreach(checkUnreachableMember)
            stat.members.foreach(member => if (!upNodes.contains(member)) upNodes = upNodes :+ member)
        case _: Ping => replyPing() //java
        case SubscribeAck(Subscribe(`topic`, None, `self`)) => println(s"[${System.currentTimeMillis()}]Subscribe  $topic Successfully")
        case ev: ServiceStatusEvents => println(s"[JAVA.ServiceMessage]${ev.toString}")
        case ev: LookingForService => println(s"[JAVA.LookingForService]${ev.toString}")
        case Consume =>
            val records = consumer.poll(Duration.ofMillis(100)).asScala
            for (record <- records) {
                println(s"${record.key}->${record.value}")
            }
        case whatever: Any => println(s"Unknown Message `$whatever` from $sender")
    }
}
