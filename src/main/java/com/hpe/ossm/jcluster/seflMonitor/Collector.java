package com.hpe.ossm.jcluster.seflMonitor;

import akka.actor.AbstractActorWithTimers;
import akka.actor.ActorRef;
import akka.cluster.Cluster;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import com.hpe.ossm.scala.lang.util.KafkaUtil;
import com.hpe.ossm.scluster.messges.CmdKPIRefresh;
import com.hpe.ossm.scluster.messges.KPIRecord;
import com.typesafe.config.Config;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import akka.kafka.scaladsl.Consumer.Control;//reuse the lib function, or to create for JAVA

import java.io.Serializable;
import java.time.Duration;
import java.util.List;

import org.json.JSONException;
import org.slf4j.LoggerFactory;

abstract public class Collector extends AbstractActorWithTimers {
    protected Logger LOGGER= LoggerFactory.getLogger(this.getClass());
    protected List<String> kpiNames;

    private final Config conf = context().system().settings().config();
    private final Cluster cluster = Cluster.get(context().system());
    private final ActorRef mediator = DistributedPubSub.get(context().system()).mediator();
    private final Materializer mat = ActorMaterializer.create(context().system());
    protected final String host = cluster.selfAddress().host().get();
    private final Boolean kafkaActive = conf.getBoolean("kafka.active");
    private final String topic = conf.getString("ossm.monitor.topic");
    private final String key_cmd = conf.getString("ossm.monitor.keys.cmd");
    private final String key_record = conf.getString("ossm.monitor.keys.record");

    private KafkaProducer<String, Serializable> producer;
    private Control consumer;
    private final String myPath = self().path().toSerializationFormatWithAddress(cluster.selfAddress());

    abstract public void initCollector();

    abstract public List<KPIRecord> collect();

    abstract public List<KPIRecord> refreshKPI(String kpiName);

    @Override
    public void preStart() throws Exception {
        super.preStart();
        LOGGER.info("Collector {} starting", myPath);
        cluster.registerOnMemberUp(() -> {
            initCollector();
            if (kafkaActive) {
                producer = KafkaUtil.createProcedure(conf);
                consumer = KafkaUtil.createAkkaConsumer(conf, myPath, topic, (ConsumerRecord<String, String> msg) -> {
                    try {
                        if (msg.key().endsWith(key_cmd)) {
                            self().tell(CmdKPIRefresh.fromString(msg.value()), self());
                        }
                    } catch (JSONException e) {
                        LOGGER.error("{} | {}", e.getMessage(), msg.value());
                    }
                    return null;
                }).run(mat);
            } else {
                mediator.tell(new DistributedPubSubMediator.Subscribe(topic, self()), self());
            }
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
        LOGGER.info("Collector {} stopped", myPath);
    }

    protected void publish(List<KPIRecord> records) {
        if (kafkaActive)
            records.forEach(r -> producer.send(new ProducerRecord<String, java.io.Serializable>(topic, key_record, r.toString())));
        else
            records.forEach(r -> mediator.tell(new DistributedPubSubMediator.Publish(topic, r), self()));
    }

    protected void setTimer(int interval) {
        if (interval > 0) timers().startPeriodicTimer("collect", "Collect", Duration.ofSeconds(interval));
    }

    protected void stopSelf() {
        context().stop(self());
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(CmdKPIRefresh.class, cmd -> {
                    if (kpiNames.contains(cmd.kpiName()) && (host.equals(cmd.host())) || "*".equals(cmd.host())) {
                        publish(refreshKPI(cmd.kpiName()));
                    }
                })
                .matchEquals("Collect", cmd -> {
                    publish(collect());
                })
                .build();
    }
}
