package com.hpe.ossm.scluster.selfMonitor.collector.impl

import com.hpe.ossm.scala.lang.util.Util.convertList
import com.hpe.ossm.scluster.messges.{KPIRecord, KPIValueType}
import com.hpe.ossm.scluster.selfMonitor.Collector
import com.typesafe.config.ConfigFactory
import org.json.JSONArray

import sys.process._
import scala.collection.JavaConverters._

class OSSMProcessMonitor extends Collector {
    override val kpiNames: List[String] = List("process_info")
    private var p: String = _

    /**
     * Get configurations and start timer to execute the 'collect' function
     *
     */
    override def initCollector(): Unit = {
        val conf = ConfigFactory.load("selfmonitor.conf").getConfig("collector.process_info")
        p = conf.getString("scripts.info")
        setTimer(conf.getInt("interval"))
    }

    private def getInfo: Option[KPIRecord] = {
        try {
            val rs = p.!!.split("\n")
            val a = (for (r <- rs if r.trim.nonEmpty) yield r).toList.asJava
            Some(KPIRecord(host, "OSSM Processes", "process_info", new JSONArray(a).toString, KPIValueType.JSON_ARRAY, "NA", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                LOGGER.error(s"Failed to get process info ${e.getMessage}")
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
