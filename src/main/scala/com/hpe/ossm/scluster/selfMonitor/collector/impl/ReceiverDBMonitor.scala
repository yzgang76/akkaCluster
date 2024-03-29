package com.hpe.ossm.scluster.selfMonitor.collector.impl

import java.sql.{Connection, ResultSet, Statement}
import com.hpe.ossm.scala.lang.util.TomcatJdbcConnection
import com.hpe.ossm.scluster.messges.{KPIRecord, KPIValueType}
import com.hpe.ossm.scluster.selfMonitor.Collector
import com.typesafe.config.ConfigFactory
import org.json.JSONObject
import scala.collection.JavaConverters._
import com.hpe.ossm.scala.lang.util.Util._

class ReceiverDBMonitor extends Collector {
    override val kpiNames = List("row_counter_per_table", "h2_benchmark", "query_statistic",
        "max_execution_count", "max_average_execution_time", "max_cumulative_execution_time",
        "max_execution_count_by_dimension", "max_average_execution_time_by_dimension", "max_cumulative_execution_time_by_dimension")

    private var sqlBenchmark: String = _
    private var sqlStatistics: String = _
    private var dimensions: java.util.List[String] = _
    private var topN: Int = _
    private val sqlRCPT =
        """SELECT TABLE_NAME,ROW_COUNT_ESTIMATE
                FROM INFORMATION_SCHEMA.TABLES
                Where TABLE_SCHEMA='PUBLIC'"""
    private val MO = "Receiver H2"

    override def initCollector(): Unit = {
        val conf = ConfigFactory.load("selfmonitor.conf").getConfig("collector.receive_db_monitor")
        //        host = conf.getString("host")
        val interval = conf.getInt("interval")
        dimensions = conf.getStringList("dimensions")
        sqlBenchmark = conf.getString("sql_benchmark")
        topN = conf.getInt("top")
        sqlStatistics = "select * from INFORMATION_SCHEMA.QUERY_STATISTICS where SQL_STATEMENT like '" + sqlBenchmark.replace("'", "''") + "'"
        setTimer(interval)
    }

    private def getRCPT(conn: Connection): Option[KPIRecord] = {
        val results = new java.util.HashMap[String, java.io.Serializable]()
        var stat: Statement = null
        var rs: ResultSet = null
        try {
            stat = conn.createStatement()
            rs = stat.executeQuery(sqlRCPT)
            while (rs.next) results.put(rs.getString(1), rs.getInt(2))
            Some(KPIRecord(host, MO, "row_counter_per_table", new JSONObject(results).toString, KPIValueType.JSON_OBJECT, "NA", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                println(s"SQL Error ${e.getMessage}")
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            closeNN(rs, stat)
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
            Some(KPIRecord(host, sqlBenchmark, "h2_benchmark", t1.toString, KPIValueType.SINGLE_OBJECT, "ms", System.currentTimeMillis()))
        } catch {
            case e: Exception =>
                println(s"SQL Error ${e.getMessage}")
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            closeNN(rs, stat)
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
                Some(KPIRecord(host, sqlBenchmark, "query_statistic", new JSONObject(rowData).toString, KPIValueType.JSON_OBJECT, "ms", System.currentTimeMillis()))
            } else {
                None
            }
        } catch {
            case e: Exception =>
                println(s"SQL Error ${e.getMessage}")
                LOGGER.error(s"SQL Error ${e.getMessage}")
                None
        } finally {
            closeNN(rs, stat)
        }
    }

    private def getTopSqls(conn: Connection, kpiName: String): List[Option[KPIRecord]] = {
        def _getSql(c: String): String = s"select $c,sql_statement from INFORMATION_SCHEMA.QUERY_STATISTICS  where sql_statement not like 'SET%' and sql_statement!='ROOLBACK' order by $c desc limit $topN"

        def _getSql2(c: String, d: String): String = s"select $c,sql_statement from INFORMATION_SCHEMA.QUERY_STATISTICS where sql_statement like '%$d%' and sql_statement not like '%QUERY_STATISTICS%' order by $c desc limit $topN"

        def _getKPI(stat: Statement, kpiName: String, sql: String, m: String): Option[KPIRecord] = {
            var rs: ResultSet = null
            try {
                rs = stat.executeQuery(sql)
                val rowData = new java.util.HashMap[java.io.Serializable, java.io.Serializable]()
                while (rs.next) rowData.put(rs.getString(2), rs.getObject(1).asInstanceOf[java.io.Serializable])
                Some(KPIRecord(host, m, kpiName, new JSONObject(rowData).toString, KPIValueType.JSON_OBJECT, "s", System.currentTimeMillis()))
            } catch {
                case e: Exception =>
                    println(s"SQL Error ${e.getMessage}")
                    LOGGER.error(s"SQL Error ${e.getMessage}")
                    None
            } finally {
                closeNN(rs)
            }
        }

        var stat: Statement = null
        try {
            stat = conn.createStatement()
            kpiName match {
                case "max_execution_count" => List(_getKPI(stat, "max_execution_count", _getSql("execution_count"), MO))
                case "max_average_execution_time" => List(_getKPI(stat, "max_execution_count", _getSql("average_execution_time"), MO))
                case "max_cumulative_execution_time" => List(_getKPI(stat, "max_execution_count", _getSql("cumulative_execution_time"), MO))
                case "max_execution_count_by_dimension" => (for (d <- dimensions.asScala) yield _getKPI(stat, "max_execution_count_by_dimension", _getSql2("execution_count", d), d)).toList
                case "max_average_execution_time_by_dimension" => (for (d <- dimensions.asScala) yield _getKPI(stat, "max_average_execution_time_by_dimension", _getSql2("average_execution_time", d), d)).toList
                case "max_cumulative_execution_time_by_dimension" => (for (d <- dimensions.asScala) yield _getKPI(stat, "max_cumulative_execution_time_by_dimension", _getSql2("cumulative_execution_time", d), d)).toList
                case _ =>
                    List.concat(
                        List(
                            _getKPI(stat, "max_execution_count", _getSql("execution_count"), MO),
                            _getKPI(stat, "max_average_execution_time", _getSql("average_execution_time"), MO),
                            _getKPI(stat, "max_cumulative_execution_time", _getSql("cumulative_execution_time"), MO)
                        ),
                        (for (d <- dimensions.asScala) yield _getKPI(stat, "max_execution_count_by_dimension", _getSql2("execution_count", d), d)).toList,
                        (for (d <- dimensions.asScala) yield _getKPI(stat, "max_average_execution_time_by_dimension", _getSql2("average_execution_time", d), d)).toList,
                        (for (d <- dimensions.asScala) yield _getKPI(stat, "max_cumulative_execution_time_by_dimension", _getSql2("cumulative_execution_time", d), d)).toList
                    )
            }
        } catch {
            case e: Exception =>
                LOGGER.error(s"SQL Error ${e.getMessage}")
                null
        } finally {
            closeNN(stat)
        }
    }

    override def collect: List[KPIRecord] = {
        val conn = TomcatJdbcConnection.getConnection("QC").orNull
        try {
            if (conn != null) {
                convertList(List.concat(List(
                    getRCPT(conn),
                    getBenchmark(conn),
                    getStatistics(conn)
                ), getTopSqls(conn, null)))
            } else {
                List.empty[KPIRecord]
            }
        } finally {
            closeNN(conn)
        }
    }

    /**
     * function to collect KPI (name as kpiName) on command
     */
    override def refreshKPI(kpiName: String): List[KPIRecord] = {
        val conn = TomcatJdbcConnection.getConnection("QC").orNull
        try {
            if (conn != null) {
                convertList(kpiName match {
                    case "row_counter_per_table" => List(getRCPT(conn))
                    case "h2_benchmark" => List(getBenchmark(conn))
                    case "query_statistic" => List(getStatistics(conn))
                    case "max_execution_count" => getTopSqls(conn, "max_execution_count")
                    case "max_average_execution_time" => getTopSqls(conn, "max_average_execution_time")
                    case "max_cumulative_execution_time" => getTopSqls(conn, "max_cumulative_execution_time")
                    case "max_execution_count_by_dimension" => getTopSqls(conn, "max_execution_count_by_dimension")
                    case "max_average_execution_time_by_dimension" => getTopSqls(conn, "max_average_execution_time_by_dimension")
                    case "max_cumulative_execution_time_by_dimension" =>getTopSqls(conn, "max_cumulative_execution_time_by_dimension")
                    case _ => List.empty[Option[KPIRecord]]
                })
            } else {
                List.empty[KPIRecord]
            }
        } finally {
            closeNN(conn)
        }
    }

    override def postStop(): Unit = {
        super.postStop()
        TomcatJdbcConnection.closeConnection("QC")
    }
}
