# 31-消息的消费总流程

![img](https://user-images.githubusercontent.com/48977889/174946914-56e1d9a3-ddb7-4409-b8de-6afaab9bf820.png)

首先要明确一点：1个Partition不能被**同组的多个Consumer**消费，但是1个Consumer可以消费多个Partition。**并且！！！如果1个Parition被多个Consumer组消费了，那么同一个消息会被这些Consumer组共同消费，类似发布订阅的模式。 **

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

   Consumer1：

   ```
   收到topic=World的消息，它的key是key :24，value是Kafka,you are the world :24，partition是1
   收到topic=World的消息，它的key是key :25，value是Kafka,you are the world :25，partition是1
   收到topic=World的消息，它的key是key :26，value是Kafka,you are the world :26，partition是1
   收到topic=World的消息，它的key是key :27，value是Kafka,you are the world :27，partition是0
   收到topic=World的消息，它的key是key :28，value是Kafka,you are the world :28，partition是2
   收到topic=World的消息，它的key是key :29，value是Kafka,you are the world :29，partition是2
   收到topic=World的消息，它的key是key :30，value是Kafka,you are the world :30，partition是2
   收到topic=World的消息，它的key是key :34，value是Kafka,you are the world :34，partition是2
   收到topic=World的消息，它的key是key :35，value是Kafka,you are the world :35，partition是2
   收到topic=World的消息，它的key是key :39，value是Kafka,you are the world :39，partition是2
   收到topic=World的消息，它的key是key :42，value是Kafka,you are the world :42，partition是2
   收到topic=World的消息，它的key是key :46，value是Kafka,you are the world :46，partition是2
   收到topic=World的消息，它的key是key :50，value是Kafka,you are the world :50，partition是2
   收到topic=World的消息，它的key是key :51，value是Kafka,you are the world :51，partition是2
   收到topic=World的消息，它的key是key :55，value是Kafka,you are the world :55，partition是2
   ```

   Consumer2：

   ```
   收到topic=World的消息，它的key是key :31，value是Kafka,you are the world :31，partition是1
   收到topic=World的消息，它的key是key :32，value是Kafka,you are the world :32，partition是1
   收到topic=World的消息，它的key是key :33，value是Kafka,you are the world :33，partition是0
   收到topic=World的消息，它的key是key :36，value是Kafka,you are the world :36，partition是1
   收到topic=World的消息，它的key是key :37，value是Kafka,you are the world :37，partition是1
   收到topic=World的消息，它的key是key :38，value是Kafka,you are the world :38，partition是1
   收到topic=World的消息，它的key是key :40，value是Kafka,you are the world :40，partition是0
   ```

   可以看到，分区1和分区0被再平衡给Consumer2消费了，Consumer1只消费分区2。

5. 然后再启动Consumer3：

   Consumer2：

   ```
   收到topic=World的消息，它的key是key :56，value是Kafka,you are the world :56，partition是1
   收到topic=World的消息，它的key是key :60，value是Kafka,you are the world :60，partition是0
   收到topic=World的消息，它的key是key :61，value是Kafka,you are the world :61，partition是1
   收到topic=World的消息，它的key是key :63，value是Kafka,you are the world :63，partition是0
   收到topic=World的消息，它的key是key :64，value是Kafka,you are the world :64，partition是1
   收到topic=World的消息，它的key是key :65，value是Kafka,you are the world :65，partition是1
   收到topic=World的消息，它的key是key :75，value是Kafka,you are the world :75，partition是1
   收到topic=World的消息，它的key是key :78，value是Kafka,you are the world :78，partition是1
   收到topic=World的消息，它的key是key :79，value是Kafka,you are the world :79，partition是1
   收到topic=World的消息，它的key是key :81，value是Kafka,you are the world :81，partition是1
   收到topic=World的消息，它的key是key :84，value是Kafka,you are the world :84，partition是1
   ```

   Consumer3：

   ```
   收到topic=World的消息，它的key是key :67，value是Kafka,you are the world :67，partition是0
   收到topic=World的消息，它的key是key :69，value是Kafka,you are the world :69，partition是0
   收到topic=World的消息，它的key是key :70，value是Kafka,you are the world :70，partition是0
   收到topic=World的消息，它的key是key :72，value是Kafka,you are the world :72，partition是0
   收到topic=World的消息，它的key是key :73，value是Kafka,you are the world :73，partition是0
   收到topic=World的消息，它的key是key :76，value是Kafka,you are the world :76，partition是0
   ```

   可以看到，分区0被再平衡给Consumer3消费了，Consumer2只消费分区1。

6. 再关掉Consumer3：

   此时Consumer1和Consumer2还是照常消费分区2和分区1，等待45秒后，发现Consumer2突然消费了很多分区0的数据（这时因为Consumer3宕机后，Producer还是正常向3个分区发送数据），这时候分区1和分区0被再平衡给Consumer2消费了。随后Consumer2开始消费分区1和0的数据：

   Consumer2：

   ```java
   收到topic=World的消息，它的key是key :191，value是Kafka,you are the world :191，partition是0
   收到topic=World的消息，它的key是key :192，value是Kafka,you are the world :192，partition是0
   收到topic=World的消息，它的key是key :193，value是Kafka,you are the world :193，partition是0
   收到topic=World的消息，它的key是key :197，value是Kafka,you are the world :197，partition是0
   收到topic=World的消息，它的key是key :199，value是Kafka,you are the world :199，partition是1
   收到topic=World的消息，它的key是key :200，value是Kafka,you are the world :200，partition是1
   收到topic=World的消息，它的key是key :201，value是Kafka,you are the world :201，partition是1
   收到topic=World的消息，它的key是key :204，value是Kafka,you are the world :204，partition是0
   收到topic=World的消息，它的key是key :205，value是Kafka,you are the world :205，partition是1
   ```

   

结论：当消费者组内的Consumer数量变化时，这个消费者组的分区分配策略会**触发再平衡**，主要区分新增和减少：

1. 新增：新Consumer会给coordinator发送JoinGroup命令，分区分配策略会触发**再平衡**。
2. 减少：需要等45秒过后，coordinator认为这个Consumer下线，将它移出消费者组，分区分配策略会触发**再平衡**。
3. 我猜测这个再平衡是基于coordinator和消费者组Leader进行的。

# 分区分配与再平衡

基于知识点36可以知道，组内的消费者数量发生变化时，分区分配策略会发生再平衡。知识点32已经说明了：**分区分配策略是通过组Leader来确定的**。那么这个件策略有哪几种呢？kafka主要提供了4种：

1. Range
2. RoundRobin
3. Sticky
4. CooperativeSticky

可以在Consumer上配置partition.assignment.strategy来确定，1个Consumer可以配置多个分配策略，默认采用Range+CooperativeSticky。当这个Consumer被选为Leader时，会采用配置好的分配策略。

## 37-Range策略：

![img](https://user-images.githubusercontent.com/48977889/175465896-ecea8440-49b4-4b55-8eac-88d61a57e56b.png)

1. Leader会将组内的Consumer进行排序，每个Consumer都有1个它的序号。

2. 通过核心公式 partitions % consumers来确定每个Consumer消费几个分区。在Consumer倒序上优先划分，当出现除不尽的情况下，排序靠前的Consumer会多消费1个分区，直到Parition被分配完。

   打个比方 7%3，还剩下1个partition。Leader会倒序给Consumer分配，先保证Consumer2消费5和6分区，再保证Consumer1消费3和4分区。最终剩下3个就让Consumer0消费。

   打个比方 8%3，还剩下2个partition。Leader会先保证Consumer0消费6和7分区。然后将3-5分区交给Consumer1，0-2分区交给Consumer0，即使是多消费分区，**也是严格按照1个Consumer多消费1个分区来划分的。**

3. 但是这种策略会引起数据倾斜，拿7%3来说，假如其他Topic也被划分成7个partition，那么在分配的时候Consumer0也会多消费1个分区。每N个这样的Topic，Consumer0就要比其他Consumer多消费N个分区。这样会增加Consumer0的消费压力，也就是数据倾斜现象。

## 38-RoundRobin

这个名字一听就知道是轮询，但是怎么轮询呢？比起Range，RoundRobin是**基于所有Topic的Partition进行分配的**。

![img](https://user-images.githubusercontent.com/48977889/175468022-8b03cddd-21f4-44b1-b24b-e07307a37e27.png)

1. Leader将所有Topic的Partition和**组内所有Consumer**列出来，使用hashcode分别对它们进行排序。
2. 得到了有序的Partition和有序的Consumer后，Leader采用轮询算法将partition按照顺序对齐，一个一个分配给Consumer。

## 39-Sticky

# 消费者的Offset

在知识点31已经知道了，Consumer会将消息offset提交coordinator所在的Broker的__consumer_offsets里，__consumer_offsets本质是1个Topic，里面采用KV结构存储，Key是group.id + 消费topic +消费的partition分区号，value是offset值。

## 40-自动提交offset

基于Consumer配置进行延时提交，通过**enable.auto.commit**开启自动提交（默认为true），通过**auto.commit.interval.ms**确定自动提交的时间间隔（默认为5秒）。当Consumer拉取一批数据A，在下一次拉取时会检查数据批次A的间隔时间是否到达5秒，如果是，则提交数据批次A最大的偏移量。这种方式代码侵入性不强，但不能保证消息可靠性，因此不建议使用。

## 41-手动提交

手动提交又区分同步提交和异步提交，这里的同步异步和Producer发消息是类似的，但异步提交没有回调，因此在可靠性要求高的场景下需要同步提交。

## 42-指定offset开始消费

Kafka有个特性是**回溯消费**，可以指定某个offset，以这个offset为起点进行消费。

## 43-指定时间开始消费

底层也是依赖知识点42的指定offset消费，只不过是先通过timeindex文件定位偏移量，再从这个偏移量开始消费。



