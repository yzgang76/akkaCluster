package com.hpe.ossm.scala.lang.util

import java.sql.{Connection, SQLException}

import com.hp.ngoss.uoc.db.utils.CenterDbConfigurationUtils
import org.apache.tomcat.jdbc.pool.DataSource
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 * util to create db connection according to the db_conf.xml on tomcat jdbc pool
 */
object TomcatJdbcConnection {
    private val pools: mutable.HashMap[String, TomcatJdbcConnection] = mutable.HashMap()
    def closeConnection(poolName: String): Unit = {
        val pool = pools.get(poolName).orNull
        if (pool != null) {
            pools.-=(poolName)
            pool.close
        }
    }

    def getConnection(poolName: String): Option[Connection] = {
        val pool = pools.get(poolName).orNull
        if (pool != null) {
            pool.getConnection
        } else {
            val newPool = new TomcatJdbcConnection(poolName)
            pools += (poolName -> newPool)
            newPool.getConnection
        }
    }

    // only for test
    def main(args: Array[String]): Unit = {
        TomcatJdbcConnection.getConnection("QC") match {
            case Some(c) =>
                c.close()
                TomcatJdbcConnection.closeConnection("QC")
            case None => println("Error")
        }
    }
}

class TomcatJdbcConnection(poolName: String) {
    private val log = LoggerFactory.getLogger(classOf[TomcatJdbcConnection])
    private val ds = new DataSource()
    private val poolProperties = CenterDbConfigurationUtils.getInstance.getPoolProperties(poolName)
    val url: String = poolProperties.getUrl

    log.info(s"******************connection info************************")
    log.info("Pool Name       : {}", poolName)
    log.info(s"DriverClassName : ${poolProperties.getDriverClassName}")
    log.info(s"DBURL           : $url")
    log.info(s"User Name       : ${poolProperties.getUsername}")
    log.info(s"MaxConnNumber   : ${poolProperties.getMaxActive}")
    log.info(s"*********************************************************")

    ds.setPoolProperties(poolProperties)

    def getConnection: Option[Connection] = {
        try {
            //Some(DriverManager.getConnection(url, props))
            Some(ds.getConnection())
        } catch {
            case e: SQLException =>
                log.error("Get Connection Failed! " + e.getMessage)
                None
        }
    }

    def close = ds.close()
}
