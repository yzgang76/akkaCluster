package com.hpe.ossm.scluster.selfMonitor.collector.impl

import com.hpe.ossm.scala.lang.util.Util.convertList
import com.hpe.ossm.scluster.messges.{Collect, KPIRecord, KPIValueType}
import com.hpe.ossm.scluster.selfMonitor.Collector
import com.typesafe.config.ConfigFactory

import sys.process._
import scala.concurrent.duration._

class OSSMPackageInfoMonitor extends Collector {
    override val kpiNames: List[String] = List("package_info")
    private var p: String = _

    /**
     * Get configurations and start timer to execute the 'collect' function
     *
     */
    override def initCollector(): Unit = {
        p=ConfigFactory.load("selfmonitor.conf").getConfig("collector.package_info").getString("scripts.info")
        timers.startSingleTimer("collect1", Collect, 5.seconds)
        timers.startSingleTimer("collect2", Collect, 15.seconds)
        timers.startSingleTimer("collect3", Collect, 60.seconds)
    }

    private def getInfo: Option[KPIRecord] = {
        try {
            Some(KPIRecord(host, "OSSM Package", "package_info", p.!!, KPIValueType.SINGLE_OBJECT, "NA", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                LOGGER.error(s"Failed to get CPU Utilization ${e.getMessage}")
                None
        }
    }
    /**
     * function to run and publish the configured KPI once
     *
     * @return
     */
    override def collect: List[KPIRecord] = {
        convertList(List(getInfo))
    }

    /**
     * function to collect KPI (name as kpiName) on command
     */
    override def refreshKPI(kpiName: String): List[KPIRecord] = collect
}
