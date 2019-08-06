package com.hpe.ossm.scluster.selfMonitor

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import com.hpe.ossm.scluster.messges.KPIRecord
import com.hpe.ossm.scluster.util.KafkaUtil
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.json.JSONException
import org.slf4j.Logger

import scala.concurrent.ExecutionContext

abstract class Listener extends Actor {
    val LOGGER: Logger
    //list of names of KPI to listener, if null, listen all KPIs
    val kpiNames: List[String]

    private val conf: Config = context.system.settings.config
    private val cluster: Cluster = Cluster(context.system)
    private val mediator = DistributedPubSub(context.system).mediator
    private var consumer: Control = _
    implicit private val ec: ExecutionContext = context.dispatcher
    implicit private val mat: Materializer = ActorMaterializer(ActorMaterializerSettings(context.system).withInputBuffer(1, 1))
    private val kafkaActive = conf.getBoolean("kafka.active")
    private val topic = conf.getString("ossm.monitor.topic")
    private val key_record = conf.getString("ossm.monitor.keys.record")
    private val myPath = self.path.toSerializationFormatWithAddress(cluster.selfAddress)
    override def preStart(): Unit = {
        println(s"Listener $myPath starting")
        super.preStart()
        cluster.registerOnMemberUp(() => {
            LOGGER.debug(s"Listener is UP, Kafka: $kafkaActive")
            if (kafkaActive) {
                consumer = KafkaUtil.createAkkaConsumer(context.system.settings.config, self.path.toSerializationFormatWithAddress(cluster.selfAddress), topic, (msg: ConsumerRecord[String, String]) => {
                    try {
                        msg.key match {
                            case `key_record` => self ! KPIRecord.fromString(msg.value)
                            case _ =>
                        }
                    } catch {
                        case e: JSONException => println(s"${e.getMessage}\n ${msg.value}")
                    }
                }).run
            } else {
                mediator ! DistributedPubSubMediator.Subscribe(topic, self)
            }
        })
    }

    override def postStop(): Unit = {
        super.postStop()
        if (kafkaActive)  consumer.stop()
        else cluster.unsubscribe(self)
        println(s"Listener $myPath stopped")
    }


    override def receive: Receive = {
        case r: KPIRecord if kpiNames == null || kpiNames.contains(r.name) =>
            LOGGER.debug(s"Received Metrics $r")
            dealWithMetric(r)
    }


    //Functions to be implemented in children

    /**
     * deal with the metrics
     *
     */
    def dealWithMetric(r: KPIRecord): Unit
}
