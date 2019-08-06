package com.hpe.ossm.jcluster;

import akka.actor.AbstractActorWithTimers;
import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.cluster.Cluster;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.kafka.scaladsl.Consumer;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import com.hpe.ossm.jcluster.messages.LookingForService;
import com.hpe.ossm.jcluster.messages.ServiceStatusEvents;
import com.hpe.ossm.scluster.util.KafkaUtil;
import com.typesafe.config.Config;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Duration;
import java.util.Deque;
import java.util.HashMap;

public abstract class ServiceEntryActor extends AbstractActorWithTimers {
    private final Logger LOGGER = LoggerFactory.getLogger(ServiceEntryActor.class);
    //service name, override in preStart
    protected String serviceName = null;
    //map to store depend service key: service name, value: list of the services in ActorRef, override in preStart
    protected HashMap<String, Deque<ActorRef>> dependServices = null;
    protected Cluster cluster = Cluster.get(context().system());
    private final ActorRef mediator = DistributedPubSub.get(context().system()).mediator();
    protected String status;
    private final String host = cluster.selfAddress().host().get();
    private final Config conf = context().system().settings().config();
    private final Boolean kafkaActive = conf.getBoolean("kafka.active");
    private final String topic = conf.getString("ossm.monitor.topic");
    private KafkaProducer<String, Serializable> producer;
    private Consumer.Control consumer;
    private final Materializer mat = ActorMaterializer.create(context().system());
    private final String myPath = self().path().toSerializationFormatWithAddress(cluster.selfAddress());

    private ServiceStatusEvents buildServiceStatusEvents() {
        return new ServiceStatusEvents(status, serviceName, getSelf(), host);
    }

    private void publishStatusChange(String newStatus) {
        status = newStatus;
        if (kafkaActive)
            producer.send(new ProducerRecord<>(topic, "status", buildServiceStatusEvents()));
        else
            mediator.tell(new DistributedPubSubMediator.Publish(topic, buildServiceStatusEvents()), getSelf());
    }

    private void lookingForDependencies() {
        if (kafkaActive)
            dependServices.forEach((k, v) ->
                    producer.send(new ProducerRecord<>(topic, "seek",
                            new LookingForService(k, self().path().toSerializationFormatWithAddress(cluster.selfAddress()))
                                    .toString())));
        else dependServices.forEach((k, v) ->
                mediator.tell(new DistributedPubSubMediator.Publish(topic,
                        new LookingForService(k, getSelf())), getSelf())
        );
    }

    @Override
    public void preStart() throws Exception {
        LOGGER.info("Service {}, {} starting", serviceName,myPath);
        super.preStart();
        if (dependServices != null && dependServices.size() > 0) status = ServiceStatusEvents.STATUS_PENDING;
        else status = ServiceStatusEvents.STATUS_AVAILABLE;
        cluster.registerOnMemberUp(() -> {
            if (kafkaActive) {
                producer = KafkaUtil.createProcedure(conf);
                consumer = KafkaUtil.createAkkaConsumer(conf, myPath.split("#")[0], topic, (ConsumerRecord<String, String> msg) -> {
                    try {
                        if ("status".equals(msg.key())) {
                            self().tell(ServiceStatusEvents.fromString(msg.value()), self());
                        } else if ("seek".equals(msg.key())) {
                            self().tell(LookingForService.fromString(msg.value()), self());
                        }
                    } catch (JSONException e) {
                        LOGGER.error("{} |  {}", e.getMessage(), msg.value());
                    }
                    return null;
                }).run(mat);
            } else {
                mediator.tell(new DistributedPubSubMediator.Subscribe(topic, getSelf()), getSelf());
            }
            //re-pub the status in case the first msg is lost
            timers().startSingleTimer("publishStatus1", "publishStatus", Duration.ofSeconds(5));
            timers().startSingleTimer("publishStatus2", "publishStatus", Duration.ofSeconds(15));
            timers().startSingleTimer("publishStatus3", "publishStatus", Duration.ofSeconds(60));
        });
    }

    @Override
    public void postStop() throws Exception {
        super.postStop();
        if (kafkaActive) {
            producer.close();
            consumer.stop();
        } else {
            cluster.unsubscribe(self());
        }
        LOGGER.info("Service {}, {} stopped", serviceName,myPath);
        publishStatusChange(ServiceStatusEvents.STATUS_UNAVAILABLE);
    }

    private boolean checkDependencies() {
        boolean ready = true;
        for (HashMap.Entry<String, Deque<ActorRef>> entry : dependServices.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                ready = false;
                break;
            }
        }
        return ready;
    }

    private boolean findService(ActorRef ref, Deque<ActorRef> services) {
        boolean find = false;
        for (ActorRef service : services) {
            if (service.path().equals(ref.path())) {
                find = true;
            }
        }
        return find;
    }

    private void removeService(ActorRef ref, Deque<ActorRef> services) {
        for (ActorRef service : services) {
            if (service.path().equals(ref.path())) {
                services.remove(ref);
                if (services.isEmpty() && !status.equals(ServiceStatusEvents.STATUS_PENDING)) {
                    publishStatusChange(ServiceStatusEvents.STATUS_PENDING);
                }
            }
        }
    }

    private void updateDependServiceByEvent(ServiceStatusEvents ev) {
        Deque<ActorRef> services = dependServices.get(ev.getServiceName());
        boolean find = findService(ev.getActorRef(), services);
        if (ev.getStatus().equals(ServiceStatusEvents.STATUS_AVAILABLE)) {  //add service
            if (services.isEmpty()) {
                services.addFirst(ev.getActorRef());
            } else { //add and sort
                if (!find) {  //sort and insert
                    //TODO: support more strategy
                    if (ev.getHost().equals(host)) { //the strategy is to use the service on the same host first
                        services.addFirst(ev.getActorRef());
                    } else {
                        services.addLast(ev.getActorRef());
                    }
                }//else do nothing; shall not
            }
            context().watch(ev.getActorRef());
            if (checkDependencies()) {
                if (!ServiceStatusEvents.STATUS_AVAILABLE.equals(status)) {
                    publishStatusChange(ServiceStatusEvents.STATUS_AVAILABLE);
                }
            }
        } else { //remove service
            removeService(ev.getActorRef(), services);
        }
    }

    /**
     * for children to get service ActorRef
     *
     * @param serviceName: name of depend service
     * @return the actorRef of service
     */
    protected ActorRef getService(String serviceName) {
        Deque<ActorRef> services = dependServices.get(serviceName);
        if (services != null) {
            return services.peekFirst();
        } else {
            return null;
        }
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ServiceStatusEvents.class, ev -> {
                    System.out.println("[ServiceStatusEvents] " + ev.toString());
                    if (dependServices != null) {
                        String serviceName = ev.getServiceName();
                        if (dependServices.containsKey(serviceName)) {  //depends on the service
                            updateDependServiceByEvent(ev);
                        }
                    }
                })
                .match(LookingForService.class, m -> {
                    System.out.println("[LookingForService] " + m.toString());
                    if (m.getServiceName().equals(serviceName) && ServiceStatusEvents.STATUS_AVAILABLE.equals(status)) {
                        m.getSeeker().tell(buildServiceStatusEvents(), getSelf());
                    }
                })
                .match(DistributedPubSubMediator.SubscribeAck.class, msg -> {
                    if (dependServices == null || dependServices.isEmpty())
                        status = ServiceStatusEvents.STATUS_AVAILABLE;
                    else status = ServiceStatusEvents.STATUS_PENDING;
                })
                .match(Terminated.class, terminated -> {
                    //TODO: if the Terminated triggered due to network issue and the network recover
                    // before the remote service(node) is removed then the service will never add to the depends
                    for (HashMap.Entry<String, Deque<ActorRef>> entry : dependServices.entrySet()) {
                        removeService(terminated.actor(), entry.getValue());
                    }
                })
                .matchEquals("publishStatus", t -> {
                    publishStatusChange(status);
                    lookingForDependencies();
                })
                .build();
    }
}