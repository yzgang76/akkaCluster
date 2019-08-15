package com.hpe.ossm.scluster.selfMonitor.collector.impl

import java.sql.{Connection, ResultSet, Statement}

import com.hpe.ossm.scala.lang.util.TomcatJdbcConnection
import com.hpe.ossm.scluster.messges.{KPIRecord, KPIValueType}
import com.hpe.ossm.scluster.selfMonitor.Collector
import com.typesafe.config.ConfigFactory
import org.json.JSONObject
import org.slf4j.{Logger, LoggerFactory}

class ReceiverDBMonitor extends Collector {
    override val LOGGER: Logger = LoggerFactory.getLogger(classOf[ReceiverDBMonitor])
    override val kpiNames = List("row_counter_per_table", "h2_benchmark", "query_statistic")

//    private var host: String = _
    private var sqlBenchmark: String = _
    private var sqlStatistics: String = _
    private val sqlRCTP =
        """SELECT TABLE_NAME,ROW_COUNT_ESTIMATE
                FROM INFORMATION_SCHEMA.TABLES
                Where TABLE_SCHEMA='PUBLIC'"""

    override def initCollector(): Unit = {
        val conf = ConfigFactory.load("selfmonitor.conf").getConfig("collector.receive_db_monitor")
//        host = conf.getString("host")
        val interval = conf.getInt("interval")
        sqlBenchmark = conf.getString("sql_benchmark")
        sqlStatistics = "select * from INFORMATION_SCHEMA.QUERY_STATISTICS where SQL_STATEMENT='" + sqlBenchmark.replace("'", "''") + "'"
        setTimer(interval)
    }

    //    private def executeSql(conn: Connection)(f: Statement => Option[KPIRecord]): Option[KPIRecord] = {
    //        var stat: Statement = null
    //        try {
    //            stat = conn.createStatement()
    //            f(stat)
    //        } catch {
    //            case e: Exception =>
    //                LOGGER.error(s"SQL Error ${e.getMessage}")
    //                None
    //        } finally {
    //            if (stat != null) stat.close()
    //        }
    //    }

    private def getRCPT(conn: Connection): Option[KPIRecord] = {
        //        executeSql(conn)((stat) => {
        //            var rs: ResultSet = null
        //            try {
        //                val results = new java.util.HashMap[String, java.io.Serializable]();
        //                rs = stat.executeQuery(sqlRCTP)
        //                while (rs.next) results.put(rs.getString(1), rs.getInt(2))
        //                Some(KPIRecord(host, "Receive H2 DB", "Row Counter per Table", new JSONObject(results).toString, KPIValueType.JSON_OBJECT, "NA", System.currentTimeMillis()))
        //            } catch {
        //                case e: Exception =>
        //                    LOGGER.error(s"SQL Error ${e.getMessage}")
        //                    None
        //            } finally {
        //                if (rs != null) rs.close()
        //            }
        //
        //        })
        val results = new java.util.HashMap[String, java.io.Serializable]()
        var stat: Statement = null
        var rs: ResultSet = null
        try {
            stat = conn.createStatement()
            rs = stat.executeQuery(sqlRCTP)
            while (rs.next) results.put(rs.getString(1), rs.getInt(2))
            Some(KPIRecord(host, "Receive H2 DB", "row_counter_per_table", new JSONObject(results).toString, KPIValueType.JSON_OBJECT, "NA", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            if (rs != null) rs.close()
            if (stat != null) stat.close()
        }
    }

    private def getBenchmark(conn: Connection): Option[KPIRecord] = {
        var stat: Statement = null
        var rs: ResultSet = null
        try {
            stat = conn.createStatement()
            val t0 = System.currentTimeMillis()
            rs = stat.executeQuery(sqlBenchmark)
            val t1 = System.currentTimeMillis() - t0
            Some(KPIRecord(host, "Receive H2 DB", "h2_benchmark", t1.toString, KPIValueType.SINGLE_OBJECT, "ms", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            if (rs != null) rs.close()
            if (stat != null) stat.close()
        }
    }

    private def getStatistics(conn: Connection): Option[KPIRecord] = {
        var stat: Statement = null
        var rs: ResultSet = null
        try {
            stat = conn.createStatement()
            rs = stat.executeQuery(sqlStatistics)
            if (rs.next) {
                val md = rs.getMetaData
                val rowData = new java.util.HashMap[String, java.io.Serializable]()
                for (i <- 1 to md.getColumnCount) rowData.put(md.getColumnName(i), rs.getObject(i).asInstanceOf[java.io.Serializable])
                Some(KPIRecord(host, "Receive H2 DB", "query_statistic", new JSONObject(rowData).toString, KPIValueType.JSON_OBJECT, "ms", System.currentTimeMillis()))
            } else {
                None
            }
        } catch {
            case e: Exception =>
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            if (rs != null) rs.close()
            if (stat != null) stat.close()
        }
    }

    override def collect: List[KPIRecord] = {
        val conn = TomcatJdbcConnection.getConnection("QC").orNull
        try {
            if (conn != null) {
                List(
                    getRCPT(conn).orNull,
                    getBenchmark(conn).orNull,
                    getStatistics(conn).orNull
                ).filter(null == _)
            } else {
                List.empty[KPIRecord]
            }
        } finally {
            if (conn != null) {
                conn.close()
            }
        }
    }


    /**
     * function to collect KPI (name as kpiName) on command
     */
    override def refreshKPI(kpiName: String): List[KPIRecord] = {
        val conn = TomcatJdbcConnection.getConnection("QC").orNull
        try {
            if (conn != null) {
                kpiName match {
                    case "row_counter_per_table" => List(getRCPT(conn).orNull).filter(_ == null)
                    case "h2_benchmark" => List(getBenchmark(conn).orNull).filter(_ == null)
                    case "query_statistic" => List(getStatistics(conn).orNull).filter(_ == null)
                    case _ => List.empty[KPIRecord]
                }
            } else {
                List.empty[KPIRecord]
            }
        } finally {
            if (conn != null) {
                conn.close()
            }
        }
    }

    override def postStop(): Unit = {
        super.postStop()
        TomcatJdbcConnection.closeConnection("QC")
    }
}
