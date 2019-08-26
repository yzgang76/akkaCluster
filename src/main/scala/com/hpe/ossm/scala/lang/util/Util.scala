package com.hpe.ossm.scala.lang.util

import com.hpe.ossm.scluster.messges.KPIRecord
import org.slf4j.{Logger, LoggerFactory}

/**
 * common util function in scala
 */
object Util {
    val LOGGER: Logger = LoggerFactory.getLogger(this.getClass)

    def ignoreError(f: () => Unit): Unit = {
        try {
            f()
        } catch {
            case _: Exception =>
        }
    }

    def convertList(l: List[Option[KPIRecord]]): List[KPIRecord] = l.filterNot(_.isEmpty).map(_.get)

    def closeNN[A <: {def close() : Unit}](obs: A*): Unit = try {
        for (a <- obs; if a != null) a.close()
    } catch {
        case e: Exception => LOGGER.error(s"Failed to close the object. ${e.getMessage}")
    }
}
