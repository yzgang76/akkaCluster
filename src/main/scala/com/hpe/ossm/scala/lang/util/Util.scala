package com.hpe.ossm.scala.lang.util

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
}
