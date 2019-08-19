package com.hpe.ossm.scluster.management

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.management.scaladsl.AkkaManagement
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import com.hpe.ossm.scluster.messges.{CmdKPIRefresh, HistoryMetric, HistoryMetricOfHost, KPIList, KPIRecord, LastNHistoryMetric, LastNHistoryMetricOfHost, MonitorMessage, SetQueue, StartPublish, StopPublish}
import com.hpe.ossm.scluster.selfMonitor.MetricsCache
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import StatusCodes._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.hpe.ossm.scluster.selfMonitor.publisher.KpiPublisher
import org.json.JSONArray

import scala.collection.JavaConverters._

class WebServer

object WebServer {
    def main(args: Array[String]) {
        val LOGGER = LoggerFactory.getLogger(classOf[WebServer])
        val config = ConfigFactory.parseString(
            s"""
                akka.cluster.roles=[ClusterManager]
            """).withFallback(ConfigFactory.load("management"))
        implicit val system: ActorSystem = ActorSystem(config.getString("Cluster_Name"), config)
        implicit val materializer: Materializer = ActorMaterializer()
        implicit val executionContext: ExecutionContext = system.dispatcher
        val host = config.getString("akka.management.http.hostname")
        val port = config.getInt("akka.management.http.port")
        system.actorOf(Props(classOf[ClusterManager]), "localListener")
        val cache = system.actorOf(Props(classOf[MetricsCache]), "MetricsCache")

        def buildMonitorMessage(kpiname: String, host: String, lastn: Int, start: Long, end: Long): MonitorMessage = {
            if (host.isEmpty) {
                if (lastn > 0) LastNHistoryMetric(kpiname, lastn)
                else HistoryMetric(kpiname, start, end)
            } else {
                if (lastn > 0) LastNHistoryMetricOfHost(kpiname, lastn, host)
                else HistoryMetricOfHost(kpiname, start, end, host)
            }
        }


        //test route shall migrate to Messager
        def flowTest: Flow[Message, Message, Any] = {
            val ref = system.actorOf(KpiPublisher.props())
            val queueSource = Source.queue[Message](1024, OverflowStrategy.backpressure)
                .conflateWithSeed(Seq(_)) { (acc, elem) => acc :+ elem }.async
                .mapMaterializedValue(ref ! SetQueue(_)).async
                .map(seq => {
                    if (seq.length > 1)
                        TextMessage(new JSONArray(asJavaCollection(seq.map(_.asTextMessage.getStrictText).toArray[String])).toString)
                    else
                        TextMessage(seq.head.asTextMessage.getStrictText)
                }).async
                .watchTermination()((_, t) => {
                    t.onComplete(_ => ref ! StopPublish)
                })
            Flow.fromSinkAndSource(Sink.foreach(s => {
                //                println(s.asTextMessage.getStrictText)
                s.asTextMessage.getStrictText match {
                    case "start" => ref ! StartPublish
                    case "stop" => ref ! StopPublish
                }
            }), queueSource)
        }

        implicit def myExceptionHandler: ExceptionHandler =
            ExceptionHandler {
                case e: Exception =>
                    complete(HttpResponse(InternalServerError, entity = s"${e.getMessage}"))
            }

        val route =
            path("kpis") {
                handleWebSocketMessages(flowTest)
            } ~
                get {
                    path("hello") {
                        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Welcome to OSSM BE AKKA Cluster Manager</h1>"))
                    } ~
                        path("kpis" / Segment) {
                            kpiName => {
                                parameters('host.?, 'last.?, 'start.?, 'end.?) {
                                    (host, lastn, start, end) => {
                                        val h = host.getOrElse("")
                                        val n = lastn.getOrElse("-1").toInt
                                        val s = start.getOrElse("-1").toLong
                                        val e = end.getOrElse("-1").toLong
                                        val msg = buildMonitorMessage(kpiName, h, n, s, e)
                                        complete(
                                            ask(cache, msg)(5.seconds).mapTo[List[KPIRecord]].map(l => {
                                                val list = new java.util.ArrayList[java.util.Map[String, java.io.Serializable]]()
                                                l.foreach(r => list.add(r.toMap))
                                                new JSONArray(list).toString
                                            }).map(c => HttpEntity(ContentTypes.`application/json`, c))
                                        )
                                    }
                                }
                            }
                        } ~
                        path("kpis") {
                            complete(
                                ask(cache, KPIList)(5.seconds).mapTo[collection.Set[String]].map(l =>
                                    new JSONArray(asJavaCollection(l)).toString
                                ).map(c => HttpEntity(ContentTypes.`application/json`, c))
                            )
                        }
                } ~
                put {
                    path("kpis" / Segment) {
                        kpiName => {
                            parameters('host.?) {
                                host => {
                                    cache ! CmdKPIRefresh(kpiName, host.orNull, System.currentTimeMillis())
                                    complete(Future {
                                        Done
                                    })
                                }
                            }
                        }
                    }
                } ~ AkkaManagement(system).routes
        val bindingFuture = Http().bindAndHandle(route, host, port)
        bindingFuture.onComplete {
            case Failure(e) => LOGGER.error(s"Failed to start. ${e.getMessage}")
            case Success(_) => LOGGER.info(s"Service start at http://$host:$port")
        }
    }
}
