package com.hpe.ossm.jcluster;

import akka.actor.AbstractActorWithTimers;
import akka.actor.Address;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.Member;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import com.hpe.ossm.jcluster.messages.Ping;
import com.hpe.ossm.jcluster.messages.Pong;
import com.hpe.ossm.jcluster.messages.UpdateClusterStatus;
import scala.collection.immutable.IndexedSeq;

import static akka.pattern.Patterns.ask;

public class LocalListener extends AbstractActorWithTimers {
    private Cluster cluster = Cluster.get(context().system());
    private int connectedSeedNodes = -1;

    @Override
    public void preStart() {
        cluster.registerOnMemberUp(() ->
                cluster.subscribe(self(), ClusterEvent.MemberUp.class, ClusterEvent.UnreachableMember.class, ClusterEvent.MemberRemoved.class)
        );
        timers().startPeriodicTimer("updateStatus", new UpdateClusterStatus(), Duration.ofSeconds(60));
    }

    @Override
    public void postStop() {
        cluster.unsubscribe(self());
    }

    private void reduceConnection(){
        connectedSeedNodes--;
        if (connectedSeedNodes == 0) {
            System.out.println("All Seed Nodes are down, showdown the node.");
            cluster.down(cluster.selfAddress());
        }
    }
    private void checkConnectionWithMaster(Address address) {
        CompletableFuture<Object> future = ask(
                context().actorSelection(address + "/user/localListener"),
                new Ping(), Duration.ofSeconds(5)).toCompletableFuture();
        try {
            if (!(future.join() instanceof Pong)) {
                System.out.println("Master " + address + " id down.");
                reduceConnection();
            }
        } catch (CompletionException e) {
            System.out.println("Master " + address + " id down.");
            reduceConnection();
        }
    }

    private void checkConnections(ClusterEvent.CurrentClusterState status) {
        IndexedSeq<Address> seedNodesAddress = cluster.settings().SeedNodes();

        Iterable<Member> members = status.getMembers();
        java.util.List<Address> memberAddress = new java.util.ArrayList<>();
        members.forEach(member -> memberAddress.add(member.address()));
        seedNodesAddress = seedNodesAddress.filter(memberAddress::contains).toIndexedSeq();

        connectedSeedNodes = seedNodesAddress.length();

        Set<Member> unReachNodes = status.getUnreachable();

        java.util.List<Address> unReachNodesAddress = new java.util.ArrayList<>();
        unReachNodes.forEach(node -> unReachNodesAddress.add(node.address()));


        IndexedSeq<Address> unReachSeedNodesAddress = seedNodesAddress.filter(unReachNodesAddress::contains).toIndexedSeq();

        if (unReachSeedNodesAddress.length() == seedNodesAddress.length()) {  //all seed node are unreached
            for (int i = 0; i < unReachSeedNodesAddress.length(); i++) {
                Address address = unReachSeedNodesAddress.apply(i);
                checkConnectionWithMaster(address);
            }
//or
//            unReachSeedNodesAddress.foreach(n->{
//                checkConnectionWithMaster(n);
//                return 1;
//            });
        }
    }

    public Receive createReceive() {
        return receiveBuilder()
                .match(ClusterEvent.CurrentClusterState.class, this::checkConnections)
                .match(ClusterEvent.MemberUp.class, mUp ->
                    System.out.println("Member is Up " + mUp.member().address())
                )
                .match(ClusterEvent.UnreachableMember.class, mUnreachable ->
                    System.out.println("Member is Unreachable " + mUnreachable.member().address())
                )
                .match(ClusterEvent.MemberRemoved.class, mRemoved ->
                    System.out.println("Member is Removed " + mRemoved.member())
                )
                .match(Ping.class, p -> {
                    System.out.println("Receive Ping from " + sender().path());
                    sender().tell(new Pong(), self());
                })
                .match(UpdateClusterStatus.class, m -> cluster.sendCurrentClusterState(self()))
                .match(ClusterEvent.MemberEvent.class, message -> System.out.println("[LL] Receive ignored message " + message.toString()))
                .build();

    }
}
