package com.hpe.ossm.jcluster.test;

import akka.actor.*;
import com.hpe.ossm.jcluster.ClusterNode;
import com.hpe.ossm.jcluster.ServiceEntryActor;
import java.util.HashMap;

public class CMService extends ServiceEntryActor {

    @Override
    public void preStart() throws  Exception {
        //override the parameters
        serviceName = "CM";
        setDependServices(null);
        super.preStart();
    }

    @Override
    public void postStop()throws  Exception {
        super.postStop();
    }

    @Override
    public AbstractActor.Receive createReceive() {
        return super.createReceive()
                .orElse(
                        receiveBuilder()
                                .match(String.class, m-> getSender().tell(cluster.selfAddress()+"|"+m, getSelf()))
                                .matchAny( o->System.out.println("Ignored message "+ o.toString()))
                                .build()
                );
    }
}

class  CMServiceApp extends ClusterNode {
    public CMServiceApp(String role){
        this.role=role;
    }
    public static void main(String[] args){
        CMServiceApp t=new CMServiceApp("CMService");
        ActorSystem sys=t.getActorSystem(null);
        sys.actorOf(Props.create(CMService.class),"CM");
    }
}

