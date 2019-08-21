package com.hpe.ossm.scala.lang.util

import com.hpe.ossm.scluster.messges.KPIRecord

/**
 * common util function in scala
 */


object Util {
    def ignoreError(f: () => Unit): Unit = {
        try {
            f()
        } catch {
            case _: Exception =>
        }
    }
    def convertList(l: List[Option[KPIRecord]]): List[KPIRecord] = l.filterNot(_.isEmpty).map(_.get)
}
