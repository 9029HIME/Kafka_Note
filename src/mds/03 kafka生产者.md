知识点4-7是预备知识点，主要是从整体上了解kafka丢消息的问题，接下来是系统地看一遍生产者相关知识。

# 9-Kafka发送消息的总流程

![image](https://user-images.githubusercontent.com/48977889/170861005-a376fbd8-c205-4224-ac52-74a181425ed3.png)

![1653819269(1)](https://user-images.githubusercontent.com/48977889/170862950-63b9a95c-74e8-4c05-9d1a-e2f99460b786.png)

1. message经历拦截器、序列化器（不用Java默认的序列化），再到分区器，分区器根据**特定规则**将消息存到RecordAccumulator的DQueue里，此时main线程的任务就结束了。
2. RecordAccumulator里的DQueue和Topic的Parition的关系一一对应。RecordAccumulator和DQueue都是在内存中创建好了，默认DQueue里的数据会被包装成一个batch，batch可以理解为DQueue里的一个节点，batch默认大小是16K。DQueue作为一个双端队列，实际上使用了内存池化思想，这也是为什么RecordAccumulator默认大小是32M（可以配置大小）。
3. Sender线程会将**触发阈值（ 知识点6）**的batch发到Broker，不过Sender会将多个batch包装成Request放到InFlightRequests里（这也是为什么kafka吞吐量达的原因，多个消息 → 1个batch，多个batch → 1个Request），过程是将DQueue的<Partition，List<ProducerBatch>>包装成InFlightRequests的<Broker，List<Request>>。**值得注意的是，对于1个Sender来说，1个Broker最多有5个Request。**这个5的含义代表Sender可以不等待ack、向1个Broker节点最多能发5个请求（可以通过max.in.flight.requests.per.connection配置），在收到第一个Request的ack之前不会包装第6个Request发送。有点类似**滑动窗口机制**。
4. 1个Producer只有1个Sender线程，1个线程管理这个Producer要发送的所有数据，底层还是基于Selector多路复用机制。基于Selector来完成发送和ack的接收。当Selector收到ack后，Sender会将DQueue里的batch清理掉。如果超过一定时间没收到ack，Producer会重发Request，重拾次数默认是Max(Integer)。

总的来说，在api每次发送1个message，多个message到了DQueue里形成1个batch，多个batch在sender里又被封装成1个Request。1个Request才是真正意义上Producer给Broker发送的内容，不论是ack还是retry都是针对Request来说的。当然，Sender再收到1个ack后会落实到每个message的callback调用（通过kafka-producer-network-thread来处理），这就涉及到细节层面了。

# Producer的发送（Java普通代码）

先创建一个topic，名字叫World：

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-topics.sh --bootstrap-server localhost:9092,192.168.120.121:9092 --topic World --create --partitions 1 --replication-factor 3
Created topic Hello.
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ 
```

其实localhost:9092,192.168.120.121:9092改成localhost:9092也可以，只要能访问到kafka其中一个节点，其他节点都能被创建partition或replica。**这里只是为了防止创建分片时，其中一个节点不可访问导致创建失败的情况。**

测试一下发送和消费的过程：

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-console-producer.sh --bootstrap-server localhost:9092 --topic World
>ping
>pong
>what?
>
```

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-console-consumer.sh --bootstrap-server localhost:9092 --property print.key=true  --topic World
null    ping
null    pong
null    what?
```

创建好单元测试的代码：

```java
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
}
```

## 10-Kafka异步发送的使用

```java
@Test
public void asyncTest(){
    // 创建producer
    KafkaProducer<String,String> producer = new KafkaProducer<String, String>(properties);

    producer.send(new ProducerRecord<>(
            "World",
            "Kafka,you are the world"
    ));

    producer.close();
}
```

此时客户端的控制台响应：

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-console-consumer.sh --bootstrap-server localhost:9092 --property print.key=true  --topic World
null    Kafka,you are the world
```

甚至还可以自定义回调函数，当producer收到broker的ack后自动调用：

```java
@Test
public void asyncTest(){
    // 创建producer
    KafkaProducer<String,String> producer = new KafkaProducer<String, String>(properties);

    String msg = "Kafka,you are the world";

    producer.send(new ProducerRecord<>(
            "World",msg
    ), new Callback() {
        @Override
        public void onCompletion(RecordMetadata recordMetadata, Exception e) {
            String topic = recordMetadata.topic();
            int partition = recordMetadata.partition();
            System.out.println(String.format("给主题%s的分区%s发送了消息：%s",topic,partition,msg));
        }
    });
    producer.close();
}
```

## 11-Kafka同步发送的使用

回忆一下知识点6，异步发送是main线程不等待ack，直接将message扔到DQueue里（当然Sender也有一个5的发送阈值控制）。而同步发送是业务系统在将message扔到DQueue后，会等待这个message的ack，再获取到ack之前不会发送下一条消息。以下是api控制：

```java
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
            String topic = recordMetadata.topic();
            int partition = recordMetadata.partition();
            System.out.println(String.format("给主题%s的分区%s发送了消息：%s",topic,partition,msg));
        }
    }).get();
    producer.close();
}
```

其实很简单，只是在send()后调用get()，方法就会一直阻塞着，有点类似Future的用法。**当然，同步发送的思想是违背Kafka高吞吐量设计理念的**。

## 12-Kafka分区策略

Kafka的消息最终落实到哪个Partition，其实是通过Producer来确定的，有以下这么几种情况：

1. 发送消息时消息没有指定所属Partition：

   将消息key的hash值%Partition个数，这个结果即消息被发送到的Partition值。

2. 发送消息时消息没有指定所属Partition，**并且没有指定消息的key**：

   **这类消息**会采取黏性分区策略，Producer会随机选择1个Partition，将该消息发送到这个Partition内。如果这个Partition在这个Producer的batch已经被发送了（知识点9），Producer会再选出1个Partition进行使用（不会和上一次的选择相同）。

3. 发送消息时没有消息所属指定Partition：

   将这个消息发送到指定的Partition里。

## 13-自定义分区器

使用Kafka默认的分区策略肯定有局限性，因此需要使用自定义的分区器，原理很简单，实现Partitioner接口即可，partition方法的返回值是分区值：

```java
public class MyPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        return 0;
    }

    @Override
    public void close() {
        

    }

    @Override
    public void configure(Map<String, ?> map) {

    }
}
```

当然，自定义后需要注入进容器内。

## 14-优化发送策略

主要从4个方向入手：

1. 增大batch大小，增加吞吐量，但削弱了实时性。
2. 增大batch停留时间，增加了吞吐量，也削弱了实时性。
3. 压缩message，kafka支持3种压缩算法：lz4、snappy、gzip，在同等batch大小情况下增加了吞吐量，也削弱了实时性。
4. 增大RecordAccumulator容量，特别是对于大分区数来说，可以增加消息的吞吐量，不会影响消息的实时性。

总的来说，kafka是一个更注重吞吐量的消息队列，不太符合实时性要求特别高场景。

## 15-生产者消息的可靠性

建议直接看**02 kafka的消息丢失.md**

## 16-Producer的幂等性

Producer的幂等性指的是Producer不论给Broker**重发**多少次**相同的message**，Broker都只会刷盘1条，重复的message会直接丢掉。那Broker根据什么来判断1个message是否重复呢？是PID、Partition、SeqNumber。其中PID是Producer和Broker连接时生成的会话ID，也就是说Producer和Broker其中一个重启后，PID就会发生变化。SeqNumber指的是消息的序列号，它是单调递增的。所以kafka的幂等性**只能在同一个会话、同一个Partition内保证**：

![image](https://user-images.githubusercontent.com/48977889/170862911-34451640-5655-47c1-bd24-2e76e8f94927.png)

## 17-消息的有序性

Kafka只能保证同一个Partition内，message被消费是有序的，**如果消费者消费了多个Partition的message，kafka是不能保证Partition之间的数据是被有序消费的**。

当然，即使同一个Partition保持幂等性也是有条件的，需要将Producer参数max.in.flight.requests.per.connection设的值≤5。Broker会将Producer在发过来的**最近5个Request数据**缓存起来，并且Broker将缓存里的message刷盘之前会通过知识点16的SeqNumber进行排序，一旦发现后面的数据是乱序的，Broker就会等待顺序正确的message到来。当丢失的message被接收后，Broker会进行一次重排序，再刷盘。

但是这个5是一个固定值，如果Producer将max.in.flight.requests.per.connection的值设为＞5，Producer发了6个未ack的Request过去，就有可能导致Broker排序失败，从而导致乱序（结合知识点9）：

![image](https://user-images.githubusercontent.com/48977889/170863651-53271903-02ac-416f-8be6-b00cd5bb8a60.png)