/*
package com.hpe.ossm.scluster.management

import akka.actor.{ActorSystem, Props}
import akka.cluster.Cluster
import akka.http.scaladsl.server.Route
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.ConfigFactory

object ClusterHTTPManager2 extends App {
    //#loading
    val config= ConfigFactory.parseString(s"""
                 akka.cluster.roles=[manager]
               """)
        .withFallback(ConfigFactory.parseString(s"""
                 akka.remote.netty.tcp.port=2552
               """))
        .withFallback(ConfigFactory.parseString(s"""
                 akka.management.http.port=8557
               """))
        .withFallback(ConfigFactory.load("management"))

    val system = ActorSystem("ClusterSystem", config)
    // Automatically loads Cluster Http Routes
    AkkaManagement(system).start()
    //#loading

    //#all
    val cluster = Cluster(system)
    val allRoutes: Route = ClusterHttpManagementRoutes(cluster)
    //#all

    //#read-only
    val readOnlyRoutes: Route = ClusterHttpManagementRoutes.readOnly(cluster)
    //#read-only

    Cluster(system).registerOnMemberUp {
        system.actorOf(Props(classOf[ClusterManager],config), "localListener")
        //        SimpleClusterApp.main(Seq("2552").toArray)
    }

}*/
