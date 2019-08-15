package com.hpe.ossm.scluster

import scala.util.Success
import akka.actor.{Actor, ActorRef, Terminated, Timers}
import akka.cluster.Cluster
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import com.hpe.ossm.jcluster.messages.{LookingForService, ServiceStatusEvents}
import com.hpe.ossm.scala.lang.util.KafkaUtil
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.json.JSONException
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

abstract class ServiceEntryActor(val serviceName: String, dependServices: mutable.HashMap[String, List[ActorRef]]) extends Actor with Timers {
    private val LOGGER = LoggerFactory.getLogger(classOf[ServiceEntryActor])
    private val cluster: Cluster = Cluster(context.system)
    implicit private val ec: ExecutionContext = context.dispatcher
    implicit private val mat: Materializer = ActorMaterializer(ActorMaterializerSettings(context.system).withInputBuffer(1, 1))
    private val mediator = DistributedPubSub(context.system).mediator
    private val topic = "ServiceEvents"
    protected var status: String = if (dependServices != null && dependServices.nonEmpty) ServiceStatusEvents.STATUS_PENDING else ServiceStatusEvents.STATUS_AVAILABLE
    private val host = cluster.selfAddress.host.orNull
    //    private val port = cluster.selfAddress.port.orNull

    private val conf: Config = context.system.settings.config

    private val kafkaActive = conf.getBoolean("kafka.active")
    private var producer: KafkaProducer[String, java.io.Serializable] = _
    private var consumer: Control = _
    private val myPath = self.path.toSerializationFormatWithAddress(cluster.selfAddress)

    private def buildServiceStatusEvents(k: Boolean) =
        if (k) new ServiceStatusEvents(status, serviceName,
            self.path.toSerializationFormatWithAddress(cluster.selfAddress), host).toString
        else new ServiceStatusEvents(status, serviceName, self, host)

    private def buildLookforMsg(n: String, k: Boolean) =
        if (k) new LookingForService(n, self.path.toSerializationFormatWithAddress(cluster.selfAddress)).toString
        else new LookingForService(n, self)

    case object PublishStatus

    import org.apache.kafka.clients.producer.ProducerRecord

    private def publishStatusChange(newStatus: String): Unit = {
        status = newStatus
        val ev = buildServiceStatusEvents(kafkaActive)
        if (kafkaActive) {
            val msg = new ProducerRecord[String, java.io.Serializable](topic, "status", ev)
            producer.send(msg)
        } else {
            mediator ! DistributedPubSubMediator.Publish(topic, ev)
            //            mediator ! DistributedPubSubMediator.Publish(topic, ServiceStatus(serviceName, "up")) // test scala messages
        }
    }

    private def lookingForDependencies(): Unit = {
        if (null != dependServices)
            if (kafkaActive)
                dependServices.keySet.foreach(n =>
                    producer.send(new ProducerRecord[String, java.io.Serializable](topic, "seek", buildLookforMsg(n, kafkaActive))))
            else
                dependServices.keySet.foreach(n =>
                    mediator ! DistributedPubSubMediator.Publish(topic, buildLookforMsg(n, kafkaActive)))
    }

    override def preStart(): Unit = {
        println(s"Service Actor $serviceName -  $myPath ,${myPath.split("#")(0)}")
        super.preStart()
        cluster.registerOnMemberUp(() => {
            if (kafkaActive) {
                producer = KafkaUtil.createProcedure(conf)
                consumer = KafkaUtil.createAkkaConsumer(conf, myPath.split("#")(0), topic, (msg: ConsumerRecord[String, String]) => {
                    //                    println(s"[Kafka Record] $msg")
                    try {
                        msg.key match {
                            case "status" => self ! ServiceStatusEvents.fromString(msg.value)
                            case "seek" => self ! LookingForService.fromString(msg.value)
                            case _ =>
                        }
                    } catch {
                        case e: JSONException => println(s"${e.getMessage}\n ${msg.value}")
                    }
                }).run
            } else {
                //                println(s"[${System.currentTimeMillis()}]000000000000000000000000000000000 $serviceName ")
                mediator ! DistributedPubSubMediator.Subscribe(topic, self)
            }
            //re-pub the status in case the first msg is lost
            timers.startSingleTimer("publishStatus1", PublishStatus, 5.seconds)
            timers.startSingleTimer("publishStatus2", PublishStatus, 15.seconds)
            timers.startSingleTimer("publishStatus3", PublishStatus, 60.seconds)
        })
    }

    override def postStop(): Unit = {
        super.postStop()
        if (kafkaActive) {
            producer.close()
            consumer.stop()
        }
        else cluster.unsubscribe(self)
        publishStatusChange(ServiceStatusEvents.STATUS_UNAVAILABLE)
    }

    private def checkDependencies: Boolean = {
        dependServices.forall {
            case (_, v) => !(null == v || v.isEmpty)
        }
    }

    private def findService(ref: ActorRef, services: List[ActorRef]): Boolean = {
        services.find(_.path.equals(ref.path)).orNull != null
    }

    private def removeService(ref: ActorRef, services: List[ActorRef]): List[ActorRef] = {
        val l = services.filter(!_.path.equals(ref.path))
        if (l.isEmpty && !status.equals(ServiceStatusEvents.STATUS_PENDING)) publishStatusChange(ServiceStatusEvents.STATUS_PENDING)
        l
    }

    private def updateDependServiceByEvent(ev: ServiceStatusEvents): Unit = {
        def _updateDependServiceByEvent(ref: ActorRef, find: Boolean): Unit = {
            if (ref != null) {
                val services = dependServices(ev.getServiceName)
                if (ServiceStatusEvents.STATUS_AVAILABLE.equals(ev.getStatus)) {
                    if (services.isEmpty) {
                        dependServices(ev.getServiceName) = List[ActorRef](ref)
                    } else {
                        if (!find) {
                            if (ev.getHost.equals(host)) { //the strategy is to use the service on the same host first
                                dependServices(ev.getServiceName) = services.+:(ref)
                            } else {
                                dependServices(ev.getServiceName) = services.:+(ref)
                            }
                        }
                    }
                    context watch ref
                    if (checkDependencies) {
                        if (!ServiceStatusEvents.STATUS_AVAILABLE.equals(status)) {
                            publishStatusChange(ServiceStatusEvents.STATUS_AVAILABLE)
                        }
                    }
                } else { //remove service
                    dependServices(ev.getServiceName) = removeService(ev.getActorRef, services)
                }
            }
        }

        val services = dependServices(ev.getServiceName)
        if (ev.getActorRef == null)
            context.actorSelection(ev.getActorPath).resolveOne()(5.seconds).onComplete {
                case Success(ref) => _updateDependServiceByEvent(ref, findService(ref, services))
                case _ => println(s"Failed to solve the actor ${ev.getActorPath}")
            }
        else
            _updateDependServiceByEvent(ev.getActorRef, findService(ev.getActorRef, services))
    }

    /**
     * The method to select one service actor
     * Could be override by children
     *
     * @param serviceName : Name of service
     * @return the selected actorRef of service
     */
    protected def getService(serviceName: String): ActorRef = {
        val services = dependServices(serviceName)
        if (services != null) {
            services.head
        } else {
            null
        }
    }

    override def receive: Receive = {
        case ev: ServiceStatusEvents if dependServices != null && dependServices.contains(ev.getServiceName) =>
            println(s"[ServiceStatusEvents] ${ev.getServiceName} (${ev.getActorRef}) set status to ${ev.getStatus}.")
            updateDependServiceByEvent(ev)
        case ev: LookingForService if serviceName.equals(ev.getServiceName) && ServiceStatusEvents.STATUS_AVAILABLE.equals(status) =>
            println(s"[LookingForService] ${ev.getServiceName} from ${ev.getSeeker} | ${ev.getSeekerPath}")
            if (null != ev.getSeeker) ev.getSeeker ! buildServiceStatusEvents(false)
            else if (null != ev.getSeekerPath) {
                context.actorSelection(ev.getSeekerPath).resolveOne()(5.seconds).onComplete {
                    case Success(ref) => ref ! buildServiceStatusEvents(false)
                    case _ => println(s"Failed to solve the actor ${ev.getSeekerPath}")
                }
            }
        case _: DistributedPubSubMediator.SubscribeAck =>
            //            println(s"$self subscribe $topic succ")
            if (dependServices == null || dependServices.isEmpty) status = ServiceStatusEvents.STATUS_AVAILABLE
            else status = ServiceStatusEvents.STATUS_PENDING
        case Terminated(r) =>
            println(s"Remove service ${r.path}")
            dependServices.foreach {
                case (k, v) => dependServices(k) = removeService(r, v)
            }
        //        case Consume =>
        //            val records = consumer.poll(Duration.ofMillis(100)).asScala
        //            for (record <- records) {
        //                println(s"[receive kafka message] ${record.key()} -> ${record.value()}")
        //                try {
        //                    record.key match {
        //                        case "status" => self ! ServiceStatusEvents.fromString(record.value().asInstanceOf[String])
        //                        case "seek" => self ! LookingForService.fromString(record.value().asInstanceOf[String])
        //                        case _ =>
        //                    }
        //
        //                } catch {
        //                    case e: JSONException => println(s"${e.getMessage}\n ${record.value}")
        //                }
        //            }
        case PublishStatus =>
            //            println(s"[${System.currentTimeMillis()}]11111111111111111111111111111111111 $serviceName ")
            publishStatusChange(status)
            lookingForDependencies()
    }
}