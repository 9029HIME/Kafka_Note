# 31-消息的消费总流程

![img](https://user-images.githubusercontent.com/48977889/174946914-56e1d9a3-ddb7-4409-b8de-6afaab9bf820.png)

首先要明确一点：1个Partition不能被**同组的多个Consumer**消费，但是1个Consumer可以消费多个Partition。

Consumer对某个Partition的消费进度（offset），是保存在ZK里的，**当然新版本是维护在Broker的__consumer_offsets里，目的是减少Consumer和ZK进行冗余的IO交互。**

Consumer是必须要指定消费者组的，即使是1个单独的Consumer，它都会有1个默认的消费者组。结合上面的说法，其实是1个Partition是可以被多个消费者组消费的：

![img](https://user-images.githubusercontent.com/48977889/174947804-04e02e1a-4e7c-4b02-be7c-69e7270e5781.png)

# 32-消费者组初始化流程

![img](https://user-images.githubusercontent.com/48977889/175037083-602ebf97-8203-4750-9104-44e27910fd78.png)

1. 首先要明确2个概念：每一个Broker都有1个Coordinator组件，1个kafka集群有50个consumer_offsets分片，这50个分片均匀分布在不同Broker上。
2. 消费者启动后，都会向Broker发送一个JoinGroup请求，告诉Broker自己属于哪一个消费者组。
3. 消费者组初始化时，首先要确定一个coordinator进行协调，这一步有点像Broker初始化时选一个Controller一样。那么该选哪一个coordinator呢？有一个公式：hashCode(groupid) % consumer_offsets分片数。例如groupid的hashcode=1（假设），offsets分片数是50，那么这个消费组就会选择1%50=1，**即1号offset分片所在的Broker的coordinator进行协调**。这个coordinator会从消费者组里选一个Leader出来，进行下一步协商。**并且这个组内的所有Consumer都向这个offsets分片提交消费确认。**
4. 选好coordinator后，coordinator会将要消费者组要消费的Topic信息发给组Leader。
5. 组Leader基于Topic的分区情况，进行**分区分配**，确定**哪个Consumer消费哪个Partition**。
6. 确定好分区分配策略后，Leader会将策略发给coordinator。
7. coordinator将Leader确定好的分区分配策略，发送到每一个具体的Consumer上，告诉Consumer应该要消费哪些分区。
8. 每个Consumer都会和coordinator进行心跳（3s），如果在45s内没有任何消息，或者Consumer处理消息的时间超过5min。coordinator会将这个Consumer移出消费者组，并执行分区再分配策略。

# 33-消费者消费过程

Kafka的Consumer是通过Pull模式拉取消息的，本质是向Broker发送一个pull请求，当**特定阈值触发后**，Broker会将消息发送到Consumer的缓冲区，再经过反序列化、拦截机的处理，来到业务代码逻辑。那么特定阈值是什么呢？其实有点类似Producer发送消息：

![img](https://user-images.githubusercontent.com/48977889/175217423-1f3191b4-12a7-47cf-9b75-d88a112181ed.png)

1. 每个Consumer都会有1个ConsumerNetworkClient对象，它专门用来拉取数据。消费者调用sendFetches时本质是通过client对象处理。
2. client对象调用send方法拉取数据，其中有3个关键参数：
   1. 每次拉取数据的最小大小，默认1KB，代表Broker要是有1KB的数据就发过来。
   2. 拉取最大超时时间，默认500秒，代表Broker要是在500ms后，数据量还未到达1KB的话，就直接发过来。
   3. 每次拉取数据的最大大小，默认50M，代表Broker一次发送过来的数据，最大50M。
3. Consumer拉取消息和Producer发送消息很类似，都是基于“批次”，只不过Consumer的批次叫Fetch。
4. Broker接受请求后，根据请求的条件等待阈值触发，阈值触发（onSuccess）后将1个Fetch的数据发给Consumer。
5. Consumer用一个completedFetches队列来接受1个Fetch。实际上Consumer监听这个队列，然后获取消息，一次最多获取500条，注意！！！这里是消息条数，不是消息大小。
6. 这500条消息经过反序列化器、拦截器处理后，最终到达业务代码进行具体的消费处理。

# 消费者消费实例代码

理论部分结束，来写代码吧，注意1个细节要实现一下

1. 先启动2个Consumer消费数据，控制Producer发送速率，然后在Producer发送途中再起1个Consumer，看看是否会触发再平衡

先启动好kafka，依旧使用知识点10的World-Topic。

## 34-消费某个主题

```java
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
```

此时调用Producer发送一条消息，可以看到控制台：

```java
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
收到topic=World的消息，它的key是null，value是Kafka,you are the world，partition是0
收到topic=World的消息，它的key是null，value是Kafka,you are the world，partition是0

Process finished with exit code 130 (interrupted by signal 2: SIGINT)
```

## 35-消费某个Partition

```java
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
```

## 36-消费者组消费某个主题

后来我发现World这个Topic在初始化的时候只有1个分区，这样不好测试

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-topics.sh --bootstrap-server localhost:9092  --describe --topic World
Topic: World    TopicId: 5aBCth8iQgKYDYQImj2Cyw PartitionCount: 1       ReplicationFactor: 3    Configs: segment.bytes=1073741824
        Topic: World    Partition: 0    Leader: 0       Replicas: 0,1,2 Isr: 1,0,2
```

于是手动修改一下World的分区数，注意！！！分区数只能扩张，不能缩小：

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-topics.sh --bootstrap-server localhost:9092 -alter --partitions 3 --topic World
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-topics.sh --bootstrap-server localhost:9092  --describe --topic World
Topic: World    TopicId: 5aBCth8iQgKYDYQImj2Cyw PartitionCount: 3       ReplicationFactor: 3    Configs: segment.bytes=1073741824
        Topic: World    Partition: 0    Leader: 0       Replicas: 0,1,2 Isr: 1,0,2
        Topic: World    Partition: 1    Leader: 1       Replicas: 1,2,0 Isr: 1,2,0
        Topic: World    Partition: 2    Leader: 2       Replicas: 2,0,1 Isr: 2,0,1
```

1. 先定义好Producer代码：

   ```java
   @Test
   public void send2ConsumerGroup() throws ExecutionException, InterruptedException {
       // 创建producer
       KafkaProducer<String,String> producer = new KafkaProducer<String, String>(properties);
   
       String msg = "Kafka,you are the world :%s";
       String key = "key :%s";
   
       AtomicInteger tag = new AtomicInteger(0);
   
       while(true) {
   
           Thread.sleep(500);
   
           producer.send(new ProducerRecord<>(
                   "World", String.format(key,tag), String.format(msg,tag)
           ), new Callback() {
               @Override
               public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                   System.out.println("callback使用的线程是：" + Thread.currentThread().getName());
                   String topic = recordMetadata.topic();
                   int partition = recordMetadata.partition();
                   System.out.println(String.format("给主题%s的分区%s发送了消息：%s", topic, partition,String.format(msg,tag.get())));
               }
           });
   
           tag.getAndIncrement();
   
       }
   }
   ```

2. 准备3个消费者实例：

   ```java
   /**
    * 消费者1
    */
   @Test
   public void consumer1(){
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
   
   @Test
   public void consumer2(){
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
   
   @Test
   public void consumer3(){
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
   ```

3. 先启动1个Consumer，可以看到这个Consumer把3个Partition的消息都给消费了：

   ```
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区0发送了消息：Kafka,you are the world :1
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区2发送了消息：Kafka,you are the world :2
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区0发送了消息：Kafka,you are the world :3
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区2发送了消息：Kafka,you are the world :4
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区2发送了消息：Kafka,you are the world :5
   callback使用的线程是：kafka-producer-network-thread | producer-1
   给主题World的分区1发送了消息：Kafka,you are the world :6
   ```

   ```
   收到topic=World的消息，它的key是key :0，value是Kafka,you are the world :0，partition是0
   收到topic=World的消息，它的key是key :1，value是Kafka,you are the world :1，partition是2
   收到topic=World的消息，它的key是key :2，value是Kafka,you are the world :2，partition是0
   收到topic=World的消息，它的key是key :3，value是Kafka,you are the world :3，partition是2
   收到topic=World的消息，它的key是key :4，value是Kafka,you are the world :4，partition是2
   收到topic=World的消息，它的key是key :5，value是Kafka,you are the world :5，partition是1
   收到topic=World的消息，它的key是key :6，value是Kafka,you are the world :6，partition是0
   ```

4. 那么试着先启动Consumer1，等一段时间后再启动Consumer2：

# 分区分配与再平衡

在Consumer可以配置

Range：再平衡后，直接将宕机消费者的消费分区移到其他存活的消费者上。