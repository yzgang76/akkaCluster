package com.hpe.ossm.jcluster;

import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public abstract class ClusterNode{
    private ActorSystem system = null;
    Config config = null;
    //role of the ActorSystem
    protected String role = null;

    public ActorSystem getActorSystem(String configName) {
        if (system == null) {
            Config c = ConfigFactory.parseString("akka.cluster.roles=[" + role + "]")
                    .withFallback(ConfigFactory.parseString("akka.actor.provider=cluster"));
            if (configName != null) config = c.withFallback(ConfigFactory.load(configName));
            else config = c.withFallback(ConfigFactory.load());
            system = ActorSystem.create("ClusterSystem", config);
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
