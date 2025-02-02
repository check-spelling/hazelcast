/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.kafka.impl;

import com.hazelcast.internal.util.StringUtil;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.utils.MockTime;
import kafka.utils.TestUtils;
import kafka.zk.EmbeddedZookeeper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Time;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.hazelcast.test.HazelcastTestSupport.randomString;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KafkaTestSupport {

    private static final String ZK_HOST = "127.0.0.1";
    private static final String BROKER_HOST = "127.0.0.1";

    private EmbeddedZookeeper zkServer;
    private String zkConnect;
    private Admin admin;

    private KafkaServer kafkaServer;
    private KafkaProducer<Integer, String> producer;
    private KafkaProducer<String, String> stringStringProducer;
    private int brokerPort = -1;
    private String brokerConnectionString;

    public void createKafkaCluster() throws IOException {
        System.setProperty("zookeeper.preAllocSize", Integer.toString(128));
        zkServer = new EmbeddedZookeeper();
        zkConnect = ZK_HOST + ':' + zkServer.port();

        Properties brokerProps = new Properties();
        brokerProps.setProperty("zookeeper.connect", zkConnect);
        brokerProps.setProperty("broker.id", "0");
        brokerProps.setProperty("log.dirs", Files.createTempDirectory("kafka-").toAbsolutePath().toString());
        brokerProps.setProperty("listeners", "PLAINTEXT://" + BROKER_HOST + ":0");
        brokerProps.setProperty("offsets.topic.replication.factor", "1");
        brokerProps.setProperty("offsets.topic.num.partitions", "1");
        // we need this due to avoid OOME while running tests, see https://issues.apache.org/jira/browse/KAFKA-3872
        brokerProps.setProperty("log.cleaner.dedupe.buffer.size", Long.toString(2 * 1024 * 1024L));
        brokerProps.setProperty("transaction.state.log.replication.factor", "1");
        brokerProps.setProperty("transaction.state.log.num.partitions", "1");
        brokerProps.setProperty("transaction.state.log.min.isr", "1");
        brokerProps.setProperty("transaction.abort.timed.out.transaction.cleanup.interval.ms", "200");
        brokerProps.setProperty("group.initial.rebalance.delay.ms", "0");
        KafkaConfig config = new KafkaConfig(brokerProps);
        Time mock = new MockTime();
        kafkaServer = TestUtils.createServer(config, mock);
        brokerPort = TestUtils.boundPort(kafkaServer, SecurityProtocol.PLAINTEXT);

        brokerConnectionString = BROKER_HOST + ':' + brokerPort;
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", brokerConnectionString);
        admin = Admin.create(props);
    }

    public void shutdownKafkaCluster() {
        if (kafkaServer != null) {
            kafkaServer.shutdown();
            admin.close();
            if (producer != null) {
                producer.close();
            }
            try {
                zkServer.shutdown();
            } catch (Exception e) {
                // ignore error on Windows, it fails there, see https://issues.apache.org/jira/browse/KAFKA-6291
                if (!isWindows()) {
                    throw e;
                }
            }

            producer = null;
            kafkaServer = null;
            admin = null;
            zkServer = null;
        }
    }

    public String getZookeeperConnectionString() {
        return zkConnect;
    }

    public String getBrokerConnectionString() {
        return brokerConnectionString;
    }

    private static boolean isWindows() {
        return StringUtil.lowerCaseInternal(System.getProperty("os.name")).contains("windows");
    }

    public void createTopic(String topicId, int partitionCount) {
        List<NewTopic> newTopics = Collections.singletonList(new NewTopic(topicId, partitionCount, (short) 1));
        CreateTopicsResult createTopicsResult = admin.createTopics(newTopics);
        try {
            createTopicsResult.all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void setPartitionCount(String topicId, int numPartitions) {
        Map<String, NewPartitions> newPartitions = new HashMap<>();
        newPartitions.put(topicId, NewPartitions.increaseTo(numPartitions));
        admin.createPartitions(newPartitions);
    }

    public Future<RecordMetadata> produce(String topic, Integer key, String value) {
        return getProducer().send(new ProducerRecord<>(topic, key, value));
    }

    public Future<RecordMetadata> produce(String topic, String key, String value) {
        return getStringStringProducer().send(new ProducerRecord<>(topic, key, value));
    }

    Future<RecordMetadata> produce(String topic, int partition, Long timestamp, Integer key, String value) {
        return getProducer().send(new ProducerRecord<>(topic, partition, timestamp, key, value));
    }

    private KafkaProducer<Integer, String> getProducer() {
        if (producer == null) {
            Properties producerProps = new Properties();
            producerProps.setProperty("bootstrap.servers", BROKER_HOST + ':' + brokerPort);
            producerProps.setProperty("key.serializer", IntegerSerializer.class.getCanonicalName());
            producerProps.setProperty("value.serializer", StringSerializer.class.getCanonicalName());
            producer = new KafkaProducer<>(producerProps);
        }
        return producer;
    }

    private KafkaProducer<String, String> getStringStringProducer() {
        if (stringStringProducer == null) {
            Properties producerProps = new Properties();
            producerProps.setProperty("bootstrap.servers", BROKER_HOST + ':' + brokerPort);
            producerProps.setProperty("key.serializer", StringSerializer.class.getCanonicalName());
            producerProps.setProperty("value.serializer", StringSerializer.class.getCanonicalName());
            stringStringProducer = new KafkaProducer<>(producerProps);
        }
        return stringStringProducer;
    }

    public void resetProducer() {
        this.producer = null;
    }

    public KafkaConsumer<Integer, String> createConsumer(String... topicIds) {
        return createConsumer(IntegerDeserializer.class, StringDeserializer.class, emptyMap(), topicIds);
    }

    public <K, V> KafkaConsumer<K, V> createConsumer(
            Class<? extends Deserializer<K>> keyDeserializerClass,
            Class<? extends Deserializer<V>> valueDeserializerClass,
            Map<String, String> properties,
            String... topicIds
    ) {
        Properties consumerProps = new Properties();
        consumerProps.setProperty("bootstrap.servers", brokerConnectionString);
        consumerProps.setProperty("group.id", randomString());
        consumerProps.setProperty("client.id", "consumer0");
        consumerProps.setProperty("key.deserializer", keyDeserializerClass.getCanonicalName());
        consumerProps.setProperty("value.deserializer", valueDeserializerClass.getCanonicalName());
        consumerProps.setProperty("isolation.level", "read_committed");
        // to make sure the consumer starts from the beginning of the topic
        consumerProps.setProperty("auto.offset.reset", "earliest");
        consumerProps.putAll(properties);
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Arrays.asList(topicIds));
        return consumer;
    }

    public void assertTopicContentsEventually(
            String topic,
            Map<Integer, String> expected,
            boolean assertPartitionEqualsKey
    ) {
        try (KafkaConsumer<Integer, String> consumer = createConsumer(topic)) {
            long timeLimit = System.nanoTime() + SECONDS.toNanos(10);
            for (int totalRecords = 0; totalRecords < expected.size() && System.nanoTime() < timeLimit; ) {
                ConsumerRecords<Integer, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<Integer, String> record : records) {
                    assertEquals("key=" + record.key(), expected.get(record.key()), record.value());
                    if (assertPartitionEqualsKey) {
                        assertEquals(record.key().intValue(), record.partition());
                    }
                    totalRecords++;
                }
            }
        }
    }

    public <K, V> void assertTopicContentsEventually(
            String topic,
            Map<K, V> expected,
            Class<? extends Deserializer<K>> keyDeserializerClass,
            Class<? extends Deserializer<V>> valueDeserializerClass
    ) {
        assertTopicContentsEventually(topic, expected, keyDeserializerClass, valueDeserializerClass, emptyMap());
    }

    public <K, V> void assertTopicContentsEventually(
            String topic,
            Map<K, V> expected,
            Class<? extends Deserializer<K>> keyDeserializerClass,
            Class<? extends Deserializer<V>> valueDeserializerClass,
            Map<String, String> consumerProperties
    ) {
        try (KafkaConsumer<K, V> consumer = createConsumer(
                keyDeserializerClass,
                valueDeserializerClass,
                consumerProperties,
                topic
        )) {
            long timeLimit = System.nanoTime() + SECONDS.toNanos(10);
            Set<K> seenKeys = new HashSet<>();
            for (int totalRecords = 0; totalRecords < expected.size() && System.nanoTime() < timeLimit; ) {
                ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<K, V> record : records) {
                    assertTrue("key=" + record.key() + " already seen", seenKeys.add(record.key()));
                    V expectedValue = expected.get(record.key());
                    assertNotNull("key=" + record.key() + " received, but not expected", expectedValue);
                    assertEquals("key=" + record.key(), expectedValue, record.value());
                    totalRecords++;
                }
            }
        }
    }
}
