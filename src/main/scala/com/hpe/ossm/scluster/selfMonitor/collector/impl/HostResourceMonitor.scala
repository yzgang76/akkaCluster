package com.hpe.ossm.scluster.selfMonitor.collector.impl

import com.hpe.ossm.scluster.messges.{KPIRecord, KPIValueType}
import com.hpe.ossm.scluster.selfMonitor.Collector
import com.typesafe.config.ConfigFactory
import com.hpe.ossm.scala.lang.util.Util._
import org.json.JSONObject
import sys.process._

class HostResourceMonitor extends Collector {
    private val CPU_U = "cpu_utilization"
    private val MEM_I = "memory_info"
    override val kpiNames: List[String] = List(CPU_U, MEM_I)
    private var sCPU: String = _
    private var sMEM: String = _

    /**
     * Get configurations and start timer to execute the 'collect' function
     *
     */
    override def initCollector(): Unit = {
        val conf = ConfigFactory.load("selfmonitor.conf").getConfig("collector.host_resource")
        sCPU = conf.getString("scripts.cpu")
        sMEM = conf.getString("scripts.memory")
        setTimer(conf.getInt("interval"))
    }

    private def getCPU: Option[KPIRecord] = {
        try {
            val result = sCPU.!!
            val util = 100.0 - result.toDouble
            Some(KPIRecord(host, "CPU", CPU_U, util.toString, KPIValueType.SINGLE_OBJECT, "%", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                LOGGER.error(s"Failed to get CPU Utilization ${e.getMessage}")
                None
        }
    }

    private def getMem: Option[KPIRecord] = {
        try {
            val rs = sMEM.!!.split(" ")
            if (rs.size != 2) {
                None
            } else {
                val data = new java.util.HashMap[String, java.io.Serializable]()
                data.put("total", rs.head.toDouble)
                data.put("free", rs.last.toDouble)
                Some(KPIRecord(host, "Memory", MEM_I, new JSONObject(data).toString, KPIValueType.JSON_OBJECT, "MB", System.currentTimeMillis()))
            }
        } catch {
            case e: Exception =>
                LOGGER.error(s"Failed to get memory information ${e.getMessage}")
                None
        }
    }

    /**
     * function to run and publish the configured KPI once
     *
     * @return
     */
    override def collect: List[KPIRecord] = {
        convertList(List(getCPU, getMem))
    }

    /**
     * function to collect KPI (name as kpiName) on command
     */
    override def refreshKPI(kpiName: String): List[KPIRecord] = {
        kpiName match {
            case CPU_U => convertList(List(getCPU))
            case MEM_I => convertList(List(getMem))
            case _ => List.empty[KPIRecord]
        }
    }
}
