package com.hpe.ossm.jcluster;

import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 *  class to start a node in OSSM BE cluster
 */
public abstract class ClusterNode{
    private ActorSystem system = null;
    private Config config = null;
    // the role of the node
    protected String role;

    /**
     * to create the Actor System
     * @param configName  null for default configuration file e.g. application.conf or the defined file
     * @return the actor System
     */
    public ActorSystem getActorSystem(String configName) {
        if (system == null) {
            Config c = ConfigFactory.parseString("akka.cluster.roles=[" + role + "]")
                    .withFallback(ConfigFactory.parseString("akka.actor.provider=cluster"));
            if (configName != null) config = c.withFallback(ConfigFactory.load(configName));
            else config = c.withFallback(ConfigFactory.load());
            system = ActorSystem.create(config.getString("Cluster_Name"), config);
            system.actorOf(Props.create(LocalListener.class), "localListener");
        }
        return system;
    }

    public Config getConfig() {
        return config;
    }

    public void shutdown() {
        if (null != system) {
            system.terminate();
        }
    }
}
