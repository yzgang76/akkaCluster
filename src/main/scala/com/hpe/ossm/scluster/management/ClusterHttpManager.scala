/*
package com.hpe.ossm.scluster.management

import akka.actor.{ActorSystem, DeadLetter, Props}
import akka.cluster.Cluster
import akka.http.scaladsl.server.Route
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.ConfigFactory

object ClusterHttpManager extends App {

    //#loading
    val config= ConfigFactory.parseString(s"""
                 akka.cluster.roles=[ClusterManager]
               """).withFallback(ConfigFactory.load("management"))
    val system = ActorSystem("ClusterSystem", config)
    // Automatically loads Cluster Http Routes
    AkkaManagement(system).start()
    //#loading
    val listener = system.actorOf(Props[DeadLetterListener])
    system.eventStream.subscribe(listener, classOf[DeadLetter])
    //#all
    val cluster = Cluster(system)
    val allRoutes: Route = ClusterHttpManagementRoutes(cluster)
    //#all

    //#read-only
    val readOnlyRoutes: Route = ClusterHttpManagementRoutes.readOnly(cluster)
    //#read-only

    cluster.registerOnMemberUp {
        system.actorOf(Props(classOf[ClusterManager]), "localListener")
    }
}

*/
