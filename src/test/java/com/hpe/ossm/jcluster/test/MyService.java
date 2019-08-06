package com.hpe.ossm.jcluster.test;


import akka.actor.ActorRef;
import akka.actor.DeadLetter;
import akka.actor.Props;
import akka.actor.ActorSystem;
import com.hpe.ossm.jcluster.ClusterNode;
import com.hpe.ossm.jcluster.ServiceEntryActor;
import com.hpe.ossm.jcluster.messages.ServiceStatusEvents;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static akka.pattern.Patterns.ask;

public class MyService extends ServiceEntryActor {

    @Override
    public void preStart() throws  Exception{
        //override the parameters
        serviceName = "MyService";
        dependServices = new HashMap<>();
        dependServices.put("CM", new ArrayDeque<>());
        super.preStart();
        timers().startPeriodicTimer("service","Hello",Duration.ofSeconds(30));
    }

    @Override
    public void postStop()throws  Exception {
        super.postStop();
    }

    @Override
    public Receive createReceive() {
        return super.createReceive()
                .orElse(
                        receiveBuilder()
                                .match(String.class, m -> {
                                    if(ServiceStatusEvents.STATUS_AVAILABLE.equals(status)){
                                        ActorRef ref = getService("CM");
                                        if (ref != null) {
                                            CompletableFuture<Object> future = ask(ref, m, Duration.ofSeconds(5)).toCompletableFuture();
                                            try {
                                                String reply = (String) future.join();
                                                System.out.println("[MyService Reply] " + reply);
                                            } catch (CompletionException e) {
                                                System.out.println("Failed to ask. " + e.getMessage());
                                            }
                                        }else{
                                            System.out.println("Ref to service CM is null");
                                        }
                                    }else{
                                        System.out.println("Service is not ready");
                                    }
                                })
                                .matchAny( o->System.out.println("Ignored message "+ o.toString()))
                                .build()
                );
    }
}

class MyServiceApp extends ClusterNode {

    public static void main(String[] args) {
        MyServiceApp t = new MyServiceApp();
        t.role="MyService";
        ActorSystem sys = t.getActorSystem(null);
        sys.actorOf(Props.create(MyService.class), "myService");
    }
}
