package com.hpe.ossm.scluster

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config.{Config, ConfigFactory}
import scala.concurrent.Future

/**
 * class to start a node in OSSM BE cluster
 * @param role : the role of the node
 * @param configName : null for default configuration file e.g. application.conf or the defined file
 */
class ClusterNode(role: String, configName: String) {
    private val c= ConfigFactory.parseString(s"""
            akka.actor.provider=cluster
            akka.cluster.roles=[$role]
          """)
    val config: Config =
        if (configName != null) c.withFallback(ConfigFactory.load(configName))
        else c.withFallback(ConfigFactory.load())

    val system = ActorSystem(config.getString("Cluster_Name"), config)
    val listener: ActorRef = system.actorOf(Props(classOf[LocalListener]), "localListener")

    def shutdown(): Future[Terminated] = system.terminate
}
