package com.jflop.server.rest.runtime;

import com.jflop.server.data.AgentJVM;
import com.jflop.server.data.JacksonSerdes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * TODO: Document!
 *
 * @author artem
 *         Date: 5/14/17
 */
public class KafkaTopicConsumer {

    private final KafkaConsumer<AgentJVM, Map> consumer;
    private Map<AgentJVM, Map<String, Map<String, Object>>> agentFeatureCommands = new HashMap<>();

    public KafkaTopicConsumer(String topicName, String consumerGroup) throws IOException {
        Properties topologyProp = new Properties();
        topologyProp.load(getClass().getClassLoader().getResourceAsStream("export.properties"));

        Properties props = new Properties();
        props.put("bootstrap.servers", topologyProp.getProperty("bootstrap.servers"));
        props.put("group.id", consumerGroup);
        props.put("enable.auto.commit", "false");
        consumer = new KafkaConsumer<>(props, JacksonSerdes.AgentJVM().deserializer(), JacksonSerdes.Map().deserializer());
        consumer.subscribe(Arrays.asList(topicName));
    }

    public Map<String, Map<String, Object>> getFeatureCommands(AgentJVM key) {
        synchronized (consumer) {
            ConsumerRecords<AgentJVM, Map> records = consumer.poll(0);
            for (ConsumerRecord<AgentJVM, Map> record : records) {
                agentFeatureCommands.put(record.key(), record.value());
            }
            consumer.commitSync();
        }

        return agentFeatureCommands.remove(key);
    }
}
