package com.hpe.ossm.scala.lang.util

import java.util.Properties
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{RunnableGraph, Sink}
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer

object KafkaUtil {
    private def createProperties(config: Config, group: String, isConsumer: Boolean): Properties = {
        val props = new Properties()
        props.put("bootstrap.servers", config.getString("kafka.bootstrap-server") + ":" + config.getInt("kafka.port"))
        props.put("retries", 0.asInstanceOf[Object])
        props.put("batch.size", 16384.asInstanceOf[Object])
        props.put("buffer.memory", 33554432.asInstanceOf[Object])
        //        props.put("linger.ms", 1)
        if (isConsumer) {
            props.put("group.id", "OSSM_BE_CLUSTER" + group)
            props.put("enable.auto.commit", "true")
            props.put("auto.commit.interval.ms", "1000")
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        } else {
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        }
        props
    }

    def createProcedure(conf: Config): KafkaProducer[String, java.io.Serializable] = new KafkaProducer[String, java.io.Serializable](createProperties(conf, null, isConsumer = false))

    def createConsumer(conf: Config, group: String): KafkaConsumer[String, java.io.Serializable] = new KafkaConsumer[String, java.io.Serializable](createProperties(conf, group, isConsumer = true))


    def createAkkaConsumerWithHistoryData(conf: Config, group: String, topic: String, callback: ConsumerRecord[String, String] => Unit): RunnableGraph[Consumer.Control] =
        _createAkkaConsumer(conf, group, topic, fetchHistory = true, callback)

    def createAkkaConsumer(conf: Config, group: String, topic: String, callback: ConsumerRecord[String, String] => Unit): RunnableGraph[Consumer.Control] =
        _createAkkaConsumer(conf, group, topic, fetchHistory = false, callback)

    private def _createAkkaConsumer(conf: Config, group: String, topic: String, fetchHistory: Boolean, callback: ConsumerRecord[String, String] => Unit): RunnableGraph[Consumer.Control] = {
        val consumerSettings =
            ConsumerSettings(conf.getConfig("akka.kafka.consumer"), new StringDeserializer, new StringDeserializer).withBootstrapServers(conf.getString("kafka.bootstrap-server") + ":" + conf.getInt("kafka.port"))
                .withGroupId(group)
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, if (fetchHistory) "earliest" else "latest")
                .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
                .withProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000")
        Consumer
            .plainSource(
                consumerSettings,
                if (fetchHistory) Subscriptions.assignmentWithOffset(new TopicPartition(topic, 0), 0)
                else Subscriptions.topics(topic)
            )
            .to(Sink.foreach(msg => callback(msg))).async
    }
}
