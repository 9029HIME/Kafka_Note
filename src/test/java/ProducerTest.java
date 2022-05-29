package src.com.test;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Test;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class ProducerTest {

    Properties properties;
    {
        // 连接kafka集群，注意要开启好zookeeper和kafka集群，这里只连本机的kafka作为入口
        properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");

        // 指定消息key的序列化机制
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // 指定消息value的序列化机制
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    }

    @Test
    public void asyncTest() throws ExecutionException, InterruptedException {
        // 创建producer
        KafkaProducer<String,String> producer = new KafkaProducer<String, String>(properties);

        String msg = "Kafka,you are the world";

        producer.send(new ProducerRecord<>(
                "World",msg
        ), new Callback() {
            @Override
            public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                System.out.println("callback使用的线程是："+Thread.currentThread().getName());
                System.out.println("准备执行callback");
                String topic = recordMetadata.topic();
                int partition = recordMetadata.partition();
                System.out.println(String.format("给主题%s的分区%s发送了消息：%s",topic,partition,msg));
            }
        }).get();
        System.out.println("我已经结束发送了："+Thread.currentThread().getName());
        producer.close();
    }
}
