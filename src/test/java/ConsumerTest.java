package src.com.test;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.KafkaStorageException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Properties;

public class ConsumerTest {
    Properties properties;
    {
        // 连接kafka集群，注意要开启好zookeeper和kafka集群，这里只连本机的kafka作为入口
        properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");

        // 指定消息key的序列化机制
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // 指定消息value的序列化机制
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // 必须要指定消费者的groupId！！！！！！！！！！！
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,"test");
    }

    /**
     * 测试连通用，只消费1条数据
     */
    @Test
    public void testConsumerOneMsg(){
        String topic = "World";
        KafkaConsumer<String,String> kafkaConsumer = new KafkaConsumer<String, String>(properties);
        ArrayList<String> topics = new ArrayList<>();
        topics.add(topic);
        kafkaConsumer.subscribe(topics);

        while (true){
            ConsumerRecords<String, String> poll = kafkaConsumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : poll) {
                String key = record.key();
                String value = record.value();
                int partition = record.partition();
                System.out.println(String.format("收到topic=%s的消息，它的key是%s，value是%s，partition是%s",topic,key,value,partition));
            }
        }
    }


    /**
     * 消费特定Partition的消息
     */
    @Test
    public void testConsumerOnePartition(){
        String topic = "World";
        KafkaConsumer<String,String> kafkaConsumer = new KafkaConsumer<String, String>(properties);

        ArrayList<TopicPartition> topicPartitions = new ArrayList<>();
        topicPartitions.add(new TopicPartition(topic,0));
        kafkaConsumer.assign(topicPartitions);

        while (true){
            ConsumerRecords<String, String> poll = kafkaConsumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : poll) {
                String key = record.key();
                String value = record.value();
                int partition = record.partition();
                System.out.println(String.format("收到topic=%s的消息，它的key是%s，value是%s，partition是%s",topic,key,value,partition));
            }
        }
    }
}
