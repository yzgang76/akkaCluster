package com.hpe.ossm.jcluster.messages;

import akka.actor.ActorRef;
import lombok.Data;

@Data
public class CmdKPIColletorRefresh implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private final long ts;
    private final String kpiName;
    private final ActorRef actorRef;
    private final String actorPath;
}
