package com.hpe.ossm.scluster.messges

import java.util

import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.SourceQueueWithComplete
import org.json.{JSONArray, JSONObject}

object KPIValueType {
    val SINGLE_OBJECT = "SINGLE"
    val JSON_OBJECT = "JSON_OBJECT"
    val JSON_ARRAY = "JSON_ARRAY"
}

sealed trait MonitorMessage extends Any with java.io.Serializable

sealed trait MonitorMessageFactory {
    def fromString(s: String): MonitorMessage
}

case object KPIRecord extends MonitorMessageFactory {
    def fromString(str: String): KPIRecord = {
        val o = new JSONObject(str)
        KPIRecord(
            o.getString("host"),
            o.getString("mo"),
            o.getString("name"),
            o.getString("value"),
            o.getString("valueType"),
            try {
                o.getString("unit")
            } catch {
                case _: Exception => "NA"
            },
            o.getLong("ts")
        )
    }
}

@SerialVersionUID(1L) case class KPIRecord(host: String, mo: String, name: String, value: String, valueType: String, unit: String, ts: Long) extends MonitorMessage {
    override def toString: String = {
        val map = new util.HashMap[String, java.io.Serializable]
        map.put("host", host)
        map.put("mo", mo)
        map.put("name", name)
        map.put("value", value)
        map.put("valueType", valueType)
        map.put("unit", unit)
        map.put("ts", ts)
        new JSONObject(map).toString
    }

    def toMap: java.util.Map[String, java.io.Serializable] = {
        val map = new util.HashMap[String, java.io.Serializable]
        map.put("host", host)
        map.put("mo", mo)
        map.put("name", name)

        map.put("valueType", valueType)
        valueType match {
            case KPIValueType.JSON_ARRAY => map.put("value", new JSONArray(value).toString)
            case KPIValueType.JSON_OBJECT => map.put("value", new JSONObject(value).toString)
            case _ => map.put("value", value)
        }
        map.put("unit", unit)
        map.put("ts", ts)
        map
    }
}

case object CmdKPIRefresh extends MonitorMessageFactory {
    def fromString(str: String): CmdKPIRefresh = {
        val o = new JSONObject(str)
        CmdKPIRefresh(
            o.getString("kpiName"),
            o.getString("host"),
            o.getLong("ts"))
    }
}

@SerialVersionUID(1L) case class CmdKPIRefresh(kpiName: String, host: String, ts: Long) extends MonitorMessage

sealed trait IntervalCollectorMessage

case object Collect extends IntervalCollectorMessage

case class SetQueue(q: SourceQueueWithComplete[Message]) extends IntervalCollectorMessage

case object KPIList extends IntervalCollectorMessage

case object StartPublish extends IntervalCollectorMessage

case object StopPublish extends IntervalCollectorMessage

case class HistoryMetric(name: String, start: Long, end: Long) extends MonitorMessage

case class HistoryMetricOfHost(name: String, start: Long, end: Long, host: String) extends MonitorMessage

case class LastNHistoryMetric(name: String, lastN: Int) extends MonitorMessage

case class LastNHistoryMetricOfHost(name: String, lastN: Int, host: String) extends MonitorMessage

