package com.hpe.ossm.scluster.selfMonitor

import akka.cluster.Cluster
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import com.hpe.ossm.scluster.ServiceEntryActor
import com.hpe.ossm.scluster.messges.{CmdKPIRefresh, HistoryMetric, HistoryMetricOfHost, KPIRecord, LastNHistoryMetric, LastNHistoryMetricOfHost}
import com.hpe.ossm.scluster.util.KafkaUtil
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.json.JSONException
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class MetricsCache extends ServiceEntryActor("KPICache", null) {
    val LOGGER: Logger = LoggerFactory.getLogger(classOf[MetricsCache])
    val kpiNames: List[String] = null
    private var cache = mutable.HashMap[String, List[KPIRecord]]()
    private val maxRecord = context.system.settings.config.getInt("ossm.monitor.cache.max-record-number")
    private val cluster: Cluster = Cluster(context.system)
    private val conf: Config = context.system.settings.config
    private val mediator = DistributedPubSub(context.system).mediator
    private val kafkaActive = conf.getBoolean("kafka.active")
    private var producer: KafkaProducer[String, java.io.Serializable] = _
    private var consumer: Control = _
    private val myPath = self.path.toSerializationFormatWithAddress(cluster.selfAddress)
    implicit private val ec: ExecutionContext = context.dispatcher
    implicit private val mat: Materializer = ActorMaterializer(ActorMaterializerSettings(context.system).withInputBuffer(1, 1))
    private val topic = conf.getString("ossm.monitor.topic")
    private val key_cmd = conf.getString("ossm.monitor.keys.cmd")
    private val key_record = conf.getString("ossm.monitor.keys.record")

    override def preStart(): Unit = {
        println(s"Listener $myPath starting")
        super.preStart()
        cluster.registerOnMemberUp(() => {
            LOGGER.debug(s"Listener is UP, Kafka: $kafkaActive")
            if (kafkaActive) {
                producer = KafkaUtil.createProcedure(conf)
                consumer = KafkaUtil.createAkkaConsumerWithHistoryData(context.system.settings.config, self.path.toSerializationFormatWithAddress(cluster.selfAddress), topic, (msg: ConsumerRecord[String, String]) => {
                    try {
                        msg.key match {
                            case `key_record` => self ! KPIRecord.fromString(msg.value)
                            case _ =>
                        }
                    } catch {
                        case e: JSONException => println(s"${e.getMessage}\n from ${msg.value}")
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
        }
        else cluster.unsubscribe(self)
        println(s"Listener $myPath stopped")
    }

    def dealWithMetric(r: KPIRecord): Unit = {
        println(s"new record ${r.toString}")
        if (r != null && r.host != null) {
            val l = cache.get(r.name).orNull
            if (l == null) {
                cache += (r.name -> List(r))
            } else {
                cache(r.name) = if (l.length > maxRecord) l.drop(1).:+(r) else l.:+(r)
            }
        }
        //        println(cache)
    }

    protected def publishCmd(cmd: CmdKPIRefresh): Unit = {
        if (kafkaActive)
            producer.send(new ProducerRecord[String, java.io.Serializable](topic, key_cmd, cmd.toString))
        else
            mediator ! DistributedPubSubMediator.Publish(topic, cmd)
    }

    override def receive: Receive = super.receive.orElse({
        case r: KPIRecord if kpiNames == null || kpiNames.contains(r.name) =>
            LOGGER.debug(s"Received Metrics $r")
            dealWithMetric(r)
        case HistoryMetric(name, start, end) =>
            val to = sender
            val l = cache.get(name).orNull
            if (l == null) to ! List.empty[KPIRecord]
            else to ! l.filter(e => if (start > 0) e.ts >= start else true)
                .filter(e => if (end > 0) e.ts <= end else true)
        case HistoryMetricOfHost(name, start, end, host) =>
            val to = sender
            val l = cache.get(name).orNull
            if (l == null) to ! List.empty[KPIRecord]
            else to ! l.filter(e => host.equals(e.host))
                .filter(e => if (start > 0) e.ts >= start else true)
                .filter(e => if (end > 0) e.ts <= end else true)
        case LastNHistoryMetric(name, n) =>
            val to = sender
            val l = cache.get(name).orNull
            if (l == null) to ! List.empty[KPIRecord]
            else to ! l.takeRight(n)
        case LastNHistoryMetricOfHost(name, n, host) =>
            val to = sender
            val l = cache.get(name).orNull
            if (l == null) to ! List.empty[KPIRecord]
            else to ! l.filter(e => host.equals(e.host)).takeRight(n)
        case cmd@CmdKPIRefresh(_, _, _) if ! sender.path.toString.equals(self.path.toString) => publishCmd(cmd)
        case m: Any => println(s"[cache] unknown message $m")
    })


}
