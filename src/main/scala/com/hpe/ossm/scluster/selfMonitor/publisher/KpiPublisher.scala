package com.hpe.ossm.scluster.selfMonitor.publisher

import akka.actor.Props
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.SourceQueueWithComplete
import com.hpe.ossm.scluster.messges.{KPIRecord, SetQueue, StartPublish, StopPublish}
import com.hpe.ossm.scluster.selfMonitor.Listener
import org.slf4j.{Logger, LoggerFactory}

object KpiPublisher {
    def props() = Props(new KpiPublisher())
}

class KpiPublisher extends Listener {
    override val LOGGER: Logger = LoggerFactory.getLogger(classOf[KpiPublisher])
    override val kpiNames: List[String] = null
    private var queue: SourceQueueWithComplete[Message] = _
    private var started = false

    override def receive: Receive = super.receive.orElse {
        case SetQueue(q) if null == queue => queue = q
        case StartPublish => started = true
        case StopPublish => context stop self
    }


    /**
     * deal with the metrics
     *
     */
    override def dealWithMetric(r: KPIRecord): Unit = {
        if (null != queue && started) {
            queue.offer(TextMessage(r.toString))
        }
    }
}
