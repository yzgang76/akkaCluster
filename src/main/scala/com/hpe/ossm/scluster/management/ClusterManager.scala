package com.hpe.ossm.scluster.management

import java.time.Duration
import java.util

import akka.actor.{Actor, ActorRef, Address, RootActorPath, Timers}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberDowned, MemberUp, UnreachableMember}
import akka.cluster.metrics.StandardMetrics.HeapMemory
import akka.cluster.metrics.{ClusterMetricsChanged, ClusterMetricsExtension, NodeMetrics}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.cluster.{Cluster, Member}
import akka.pattern.ask
import akka.util.Timeout
import com.hpe.ossm.jcluster.messages.{LookingForService, Ping, Pong, ServiceStatusEvents}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import com.hpe.ossm.scala.lang.util.KafkaUtil
import com.hpe.ossm.scluster.messges.{KPIRecord, KPIValueType}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.slf4j.{Logger, LoggerFactory}
import org.json.JSONObject

import scala.collection.JavaConverters._

class ClusterManager extends Actor with Timers {
    val LOGGER: Logger = LoggerFactory.getLogger(classOf[ClusterManager])

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
    private val key_record = conf.getString("ossm.monitor.keys.record")
    private val host: String = cluster.selfAddress.host.orNull

    override def preStart: Unit = {
        println(s"Kafka is active : $kafkaActive")
        cluster.registerOnMemberUp(
            () -> {
                //register cluster metrics
                ClusterMetricsExtension(context.system).subscribe(self)

                //register cluster events
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
        if (kafkaActive) {
            producer.close()
            consumer.close()
        } else {
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

    private def publish(r: KPIRecord): Unit = {
        if (kafkaActive)
            if (r != null) producer.send(new ProducerRecord[String, java.io.Serializable](topic, key_record, r.toString))
            else LOGGER.warn(s"Receive Null KPI")
        else if (r != null) mediator ! DistributedPubSubMediator.Publish(topic, r)
        else LOGGER.warn(s"Receive Null KPI")
    }

    private def getRole(address: Address): String = {
        val r = upNodes.find(_.address.toString.equals(address.toString)).orNull
        if (r != null) r.roles.head
        else null

    }

    private def dealWithMetrics(nodeMetrics: NodeMetrics): KPIRecord = nodeMetrics match {
        case HeapMemory(address, timestamp, used, committed, max) =>

            /**
             * * @param address [[akka.actor.Address]] of the node the metrics are gathered at
             * * @param timestamp the time of sampling, in milliseconds since midnight, January 1, 1970 UTC
             * * @param used the current sum of heap memory used from all heap memory pools (in bytes)
             * * @param committed the current sum of heap memory guaranteed to be available to the JVM
             * *   from all heap memory pools (in bytes). Committed will always be greater than or equal to used.
             * * @param max the maximum amount of memory (in bytes) that can be used for JVM memory management.
             * *   Can be undefined on some OS.
             */
            val role = getRole(address)
            if (role != null) {
                val value = new java.util.HashMap[String, java.io.Serializable]()
                value.put("used", used.doubleValue / 1024 / 1024)
                value.put("committed", committed.doubleValue / 1024 / 1024)
                value.put("max", max.getOrElse(0).asInstanceOf[Long].doubleValue / 1024 / 1024)
                KPIRecord(host, role, "jvm_mem", new JSONObject(value).toString, KPIValueType.JSON_OBJECT, "MB", timestamp)
            }
            else null
        /*   case Cpu(address, timestamp, systemLoadAverage, cpuCombined, cpuStolen, processors) =>

               /**
                * *param address [[akka.actor.Address]] of the node the metrics are gathered at
                * *@param timestamp the time of sampling, in milliseconds since midnight, January 1, 1970 UTC
                * *@param systemLoadAverage OS-specific average load on the CPUs in the system, for the past 1 minute,
                * The system is possibly nearing a bottleneck if the system load average is nearing number of cpus/cores.
                * *@param cpuCombined combined CPU sum of User + Sys + Nice + Wait, in percentage ([0.0 - 1.0]. This
                * metric can describe the amount of time the CPU spent executing code during n-interval and how
                * much more it could theoretically.
                * *@param cpuStolen stolen CPU time, in percentage ([0.0 - 1.0].
                * *@param processors the number of available processors
                */
               val role = getRole(address)
               if (role != null) {
                   val value = new java.util.HashMap[String, java.io.Serializable]()
                   value.put("cpuCombined", cpuCombined.getOrElse(0).asInstanceOf[Double] * 100)
                   value.put("cpuStolen", cpuStolen.getOrElse(0).asInstanceOf[Double] * 100)
                   value.put("systemLoadAverage", systemLoadAverage.getOrElse(0).asInstanceOf[Double] * 100)
                   KPIRecord(host, role, "jvm_cpu", new JSONObject(value).toString, KPIValueType.JSON_OBJECT, "%", timestamp)
               }
               else null*/
        case _ => null
    }

    override def receive: Receive = {
        case m: MemberUp if !upNodes.contains(m.member) =>
            println(s"Up ${m.member} as ${m.member.roles}")
            upNodes = upNodes :+ m.member
        case m: MemberDowned => println(s"Downed ${m.member}")
        case um: UnreachableMember => println(s"Unreached  ${um.member}")
            checkUnreachableMember(um.member)
        case CheckUnreached => cluster.sendCurrentClusterState(self)
        case stat: CurrentClusterState =>
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
        case ClusterMetricsChanged(clusterMetrics) =>
            clusterMetrics.foreach { nodeMetrics =>
                publish(dealWithMetrics(nodeMetrics))
            }
        case whatever: Any => println(s"[ClusterManager] Ignored Message `$whatever` from $sender")
    }
}
