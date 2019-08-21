package com.hpe.lang.test


import scala.collection.mutable.HashMap

class A(val i: Int) {
    var c = 0
    val m = () => c + 1
}

class B extends A(1) {

}

object Tests {

    def a(s: String)(implicit x: Int) = s + x + 1

    def main(args: Array[String]): Unit = {
        val b = new B()
        println(b.m())
        b.c = 2
        println(b.m())

        val L = HashMap(1 -> 2, 2 -> 4)
        println(L.forall {
            case (k, v) => v % 2 == 0
        })

        var m = List[Int]()
        val map = HashMap[String, List[Int]]("a" -> m)
        m = m.:+(1)
        m = m.:+(2)
        m = m.:+(3)
        m = m.+:(4)
        println(m.toString)

        m = m.filter(_ != 2)
        map("a") = m
        //        var ll:List[Int]=map("a")
        //        ll=ll.:+(5)
        //        map("a")=ll
        implicit val x0 = 2
        println(a("a"))
        println(a("a")(1))

        map += ("b" -> List(3))
        println(map)

        println(m.takeRight(2))

        println(List(Some(1), None).filterNot(_.isEmpty).map(_.get))
        import sys.process._
        println("****************")
        val s = "c:/temp/test.bat"
        val r:String = s.!!
        println(r)
        println(Process("dir"))
    }
}
