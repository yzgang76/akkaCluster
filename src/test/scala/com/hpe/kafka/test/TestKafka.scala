package com.hpe.kafka.test

import java.time.Duration
import java.util
import java.util.Properties

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import com.typesafe.config.ConfigFactory
import kafka.admin.{AdminUtils, RackAwareMode}
import kafka.utils.ZkUtils
import org.apache.kafka.common.security.JaasUtils
import akka.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.scaladsl.{Keep, Sink}
import com.hpe.ossm.jcluster.messages.{LookingForService, ServiceStatusEvents}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.TopicPartition
import kafka.zk.AdminZkClient
import org.I0Itec.zkclient.ZkClient
import kafka.utils.ZKStringSerializer
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.common.serialization.StringDeserializer
import org.json.JSONException

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.concurrent.duration._

object TKafka {

    val bootstrapServers = "10.0.0.12:9092"

    def createTopic(topic: String): Unit = {
        val props = new Properties
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "10.0.0.12:9092")
        val adminClient = AdminClient.create(props)
        adminClient.createTopics(util.Arrays.asList(new NewTopic(topic, 2, 1)))
    }

    def deleteTopic(topic: String) = {
        val props = new Properties
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "10.0.0.12:9092")
        val adminClient = AdminClient.create(props)
        adminClient.deleteTopics(util.Arrays.asList(topic))
    }


    def commonClientTest(topic: String): Unit = {
        val props = new Properties()
        props.put("bootstrap.servers", "10.0.0.12:9092")
        //        props.put("acks", "all")
        props.put("retries", 0.asInstanceOf[Object])
        props.put("batch.size", 16384.asInstanceOf[Object])
        //        props.put("linger.ms", 1)
        props.put("buffer.memory", 33554432.asInstanceOf[Object])
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")


        val producer = new KafkaProducer[String, String](props)


        //        for (i <- 1 to 10) {
        //            val value = "value_" + i
        //            import org.apache.kafka.clients.producer.ProducerRecord
        //            val msg = new ProducerRecord[String, String](topic, value)
        //            producer.send(msg)
        //        }
        val partitions = producer.partitionsFor(topic).asScala
        for (p <- partitions) {
            println(p)
            p.partition()
        }
        //        System.out.println("send message over.")
        //        producer.close(Duration.ofSeconds(1))

        props.put("group.id", "test-1")
        props.put("enable.auto.commit", "true")
        props.put("auto.commit.interval.ms", "1000")
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        props.put("auto.offset.reset", "earliest")
        val consumer = new KafkaConsumer[String, String](props)
        val topicPartition1 = new TopicPartition(topic, 0)
        consumer.assign(util.Arrays.asList(topicPartition1))
        consumer.seek(topicPartition1, 0)
        //        consumer.subscribe(util.Arrays.asList(topic))
        while (true) {
            val records = consumer.poll(Duration.ofMillis(100)).asScala
            for (record <- records) {
                println(s"[${record.offset()}]${record.key}->${record.value}")
            }
        }
    }

    def akkaConsumeTest(topic: String): Unit = {
        val config = ConfigFactory.load("akkaKafka")
        implicit val system = ActorSystem("ClusterSystem", config)
        //        implicit val ec = system.dispatcher
        implicit val mat = ActorMaterializer(
            ActorMaterializerSettings(system)
                .withInputBuffer(1, 1)
        )


        val consumerSettings =
            ConsumerSettings(config.getConfig("akka.kafka.consumer"), new StringDeserializer, new StringDeserializer).withBootstrapServers("10.0.0.12:9092")
                .withGroupId("akkaGroup1")
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
                .withProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000")
        println("000000000")
        //        val commitSettings = CommitterSettings.apply(config.getConfig("akka.kafka.commit"))
        //        println(s"$commitSettings")

              Consumer
                  .plainSource(consumerSettings, Subscriptions.topics(topic))
                  .to(Sink.foreach(println))
                  .run()
        /*val topicPartition1 = new TopicPartition(topic, 0)
        Consumer
            .plainSource(
                consumerSettings,
                Subscriptions.assignmentWithOffset(
                    topicPartition1, 0
                )
            )
            .to(Sink.foreach(msg=>
                try {
                     msg.key match {
                        case "status" =>println(s"[${msg.offset}] ${msg.key}->${ServiceStatusEvents.fromString(msg.value)}")
                        case "seek" =>println(s"[${msg.offset}] ${msg.key}->${LookingForService.fromString(msg.value)}")
                        case _ =>
                    }

                } catch {
                    case e: JSONException => println(s"${e.getMessage}\n ${msg.value}")
                }))
            .run()*/

        def business(key: String, value: String): Future[Done] = {
            println(s"$key->$value")
            Future.successful(Done)
        }

    }

    def main(args: Array[String]): Unit = {
        //                val zkUtils = ZkUtils.
        //                    apply("10.0.0.12:2181", 30000, 30000, JaasUtils.isZkSecurityEnabled)
        //                AdminUtils.createTopic(zkUtils, "newTopic", 1, 1, new Properties(), RackAwareMode.Enforced)
        //                zkUtils.close()


        //        val conf = ConfigFactory
        //            .parseString(
        //                """
        //        akka.kafka.producer.kafka-clients.bootstrap.servers = "localhost:9092"
        //        akka.kafka.producer.kafka-clients.parallelism = 1
        //        akka.kafka.producer.kafka-clients.key.serializer = org.apache.kafka.common.serialization.StringSerializer
        //        akka.kafka.producer.kafka-clients.value.serializer = org.apache.kafka.common.serialization.StringSerializer
        //        """
        //            )
        //            .withFallback(ConfigFactory.load())
        //            .getConfig("akka.kafka.producer")
        //        val settings = ProducerSettings(conf, None, None)
        //////////////////////////////////////////////////////////////////////////////////////////////
        val topic = "ServiceEvents"
        //        createTopic(topic)
        //         deleteTopic(topic)
        //                 commonClientTest(topic)
        akkaConsumeTest(topic)


    }
}
