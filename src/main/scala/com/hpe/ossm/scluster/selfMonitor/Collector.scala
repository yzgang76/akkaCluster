package com.hpe.ossm.scluster.selfMonitor

import java.text.SimpleDateFormat

import akka.actor.{Actor, Timers}
import akka.cluster.Cluster
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import com.hpe.ossm.scluster.messges.{CmdKPIRefresh, Collect, KPIRecord}
import com.hpe.ossm.scluster.util.KafkaUtil
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.json.JSONException
import org.slf4j.Logger
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

abstract class Collector extends Actor with Timers {
    val LOGGER: Logger
    //list of names of KPI
    val kpiNames: List[String]

    protected val conf: Config = context.system.settings.config
    protected val cluster: Cluster = Cluster(context.system)
    private val mediator = DistributedPubSub(context.system).mediator
    private implicit val ec: ExecutionContext = context.dispatcher
    private implicit val mat: Materializer = ActorMaterializer.create(context.system)
    private val host = cluster.selfAddress.host.orNull
    private val kafkaActive = conf.getBoolean("kafka.active")
    private val topic = conf.getString("ossm.monitor.topic")
    private val key_cmd = conf.getString("ossm.monitor.keys.cmd")
    private val key_record = conf.getString("ossm.monitor.keys.record")
    //Kafka producer to publish KPI
    private var producer: KafkaProducer[String, java.io.Serializable] = _
    private var consumer: Control = _
    private val myPath = self.path.toSerializationFormatWithAddress(cluster.selfAddress)

    override def preStart(): Unit = {
        println(s"Collector $myPath [$kpiNames] on $host starting")
        //        super.preStart()
        cluster.registerOnMemberUp(() => {
            initCollector()
            LOGGER.debug(s"Collector is UP, Kafka: $kafkaActive")
            if (kafkaActive) {
                producer = KafkaUtil.createProcedure(conf)
                consumer = KafkaUtil.createAkkaConsumer(conf, myPath, topic, (msg: ConsumerRecord[String, String]) => {
                    //                    println(s"[Kafka Record] $msg")
                    try {
                        msg.key match {
                            case `key_cmd` => self ! CmdKPIRefresh.fromString(msg.value)
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
        if (kafkaActive) {
            producer.close()
            consumer.stop()
        } else {
            cluster.unsubscribe(self)
        }
        println(s"Collector $myPath [$kpiNames] on $host stopped")
    }

    override def receive: Receive = {
        case CmdKPIRefresh(kpiName, host, ts) if kpiNames.contains(kpiName) && ((host == null) || host.equals(this.host)) =>
            //make sure the value of 'host' from FE is same as that in BE
            LOGGER.debug(s"Received CMD to refresh the collector ${new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts)}")
            publish(collect)
        case Collect =>
            LOGGER.debug(s"Received Collect Message")
            publish(collect)
    }


    protected def publish(records: List[KPIRecord]): Unit = {
        //        println(s"publish ${record.toString}")
        if (kafkaActive)
            records.foreach(r => producer.send(new ProducerRecord[String, java.io.Serializable](topic, key_record, r.toString)))
        else
            records.foreach(r => mediator ! DistributedPubSubMediator.Publish(topic, r))
    }

    protected def setTimer(interval: Int): Unit = {
        publish(collect)  //run collector at beginning
        if (interval > 0) timers.startPeriodicTimer("collect", Collect, interval.seconds)
    }


    protected def stopSelf() = context stop self

    //Functions to be implemented in children

    /**
     * Get configurations and start timer to execute the 'collect' function
     *
     */
    def initCollector(): Unit

    /**
     * function to run and publish the configured KPI once
     *
     * @return
     */
    def collect: List[KPIRecord]
}

