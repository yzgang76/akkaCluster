package com.hpe.monitor.test

import akka.actor.{ActorRef, Props}
import akka.pattern.Patterns.ask
import com.hpe.ossm.jcluster.messages.ServiceStatusEvents
import com.hpe.ossm.scala.lang.util.Util
import com.hpe.ossm.scluster.{ClusterNode, ServiceEntryActor}
import com.hpe.ossm.scluster.messges.{Collect, KPIRecord, KPIValueType, LastNHistoryMetric}
import com.hpe.ossm.scluster.selfMonitor.{Collector, Listener, MetricsCache}
import org.json.JSONArray
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import com.hpe.ossm.jmonitor.test.CollectorInJava
object Run {
    def main(args: Array[String]): Unit = {
        new MonitorSample
    }
}

class MonitorSample extends ClusterNode("selfMonitor", null) {
    system.actorOf(Props(classOf[MyTestCollector]))
//    system.actorOf(Props(classOf[CollectorInJava]))
    system.actorOf(Props(classOf[MyListener]))
    //    system.actorOf(Props(classOf[MetricsCache]),"MetricsCache")
    //    system.actorOf(Props(classOf[CacheTest]),"CacheTest")
}

/**
 * sample class
 */
class MyTestCollector extends Collector {
    override val LOGGER: Logger = LoggerFactory.getLogger(classOf[MyTestCollector])
    override val kpiNames = List("k1", "k2")

    //collector variables
    private var host: String = _
    private var name: String = _
    private var interval: Int = _
    private var desc: String = _
    private var unit: String = _


    override def initCollector(): Unit = {
        val path = "ossm.monitor.collector.test"
        try {
            val conf = context.system.settings.config.getConfig(path)
            host = conf.getString("host")
            name = conf.getString("name")
            interval = conf.getInt("interval")
            Util.ignoreError(() => desc = conf.getString("desc"))
            Util.ignoreError(() => unit = conf.getString("unit"))

            /*
            important! to start timer
             */
            if (interval > 0) {
                println(s"start timer ${interval}s")
                import scala.concurrent.duration._
                timers.startPeriodicTimer("collect", Collect, interval.seconds)
            }

        } catch {
            case e: Exception =>
                LOGGER.error(s"Failed to load config $path, ${e.getMessage}")
                context stop self
        }
    }

    var i = 0

    override def collect: List[KPIRecord] = {
        i = i + 1
        //        KPIRecord(host, host, "k1", "kpi" + i, KPIValueType.SINGLE_OBJECT, "NA", System.currentTimeMillis())
        List(
            KPIRecord(host, host, "k1", "[v" + i + ",v]", KPIValueType.JSON_ARRAY, "NA", System.currentTimeMillis()),
            KPIRecord("localhost", "localhost", "k1", "[v" + i + ",v]", KPIValueType.JSON_ARRAY, "NA", System.currentTimeMillis()),
            KPIRecord(host, host, "k2", i + "", KPIValueType.SINGLE_OBJECT, "NA", System.currentTimeMillis()),
            KPIRecord("localhost", "localhost", "k2", i + "", KPIValueType.SINGLE_OBJECT, "NA", System.currentTimeMillis())
        )
    }

    override def receive:Receive=super.receive.orElse(
        {
            case _=>
        }
    )
}

class MyListener extends Listener {
    override val LOGGER: Logger = LoggerFactory.getLogger(classOf[MyListener])
    override val kpiNames: List[String] = null

    override def dealWithMetric(r: KPIRecord): Unit = {
        //        val ob = new JSONArray(r.value)
        println(r.toString /*+ "->"*/)
        //        println(ob)
    }
}

class CacheTest extends ServiceEntryActor("cacheTest", mutable.HashMap("KPICache" -> List.empty[ActorRef])) {
    override def preStart(): Unit = {
        super.preStart()
        timers.startPeriodicTimer("test", "test", 5.seconds)
    }

    implicit private val ec: ExecutionContext = context.dispatcher

    override def receive: Receive = super.receive.orElse({
        case "test" if ServiceStatusEvents.STATUS_AVAILABLE.equals(status) =>
            val reply = ask(getService("KPICache"), LastNHistoryMetric("k1", 2), 5.seconds).mapTo[List[KPIRecord]]
            reply.onComplete {
                case Success(value) =>
                    println(value.mkString("\n"))
                    if (value.nonEmpty) {
                        val s = new JSONArray(value.head.value)
                        println(s"---------[${s.get(0)}]")
                    }
                case Failure(exception) =>
            }
        case a: Any => println(s"[cache test]unknown message $a")
    })
}