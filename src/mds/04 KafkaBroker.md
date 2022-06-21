# 18-Kafka在ZK上存储的信息

虽然kafka3.x后不再依赖ZK管理元数据了，但还是要了解一下ZK模式的数据存储的。

Broker在连接Zookeeper后，Zookeeper主要会存以下元数据：

![image](https://user-images.githubusercontent.com/48977889/174468820-ec14cc8e-44d0-4f1e-ad64-cf89326f2e8c.png)

值得注意的是，Broker是没有主从概念的，只有Partition的Replica有主从概念。

## 19-Broker总流程

![image](https://user-images.githubusercontent.com/48977889/174469253-773af99e-e60d-4f95-9e41-5cd9ea706c9e.png)

1. Broker启动，向Zookeeper注册。
2. 每一个Broker都有自己的Controller模块。Broker抢先注册Zookeeper里的Controller模块，谁先注册，谁就是真正意义上的Controller。
3. 假设Broker0成为Controller，那么它会从zk拿到集群里的所有Broker信息，然后为每个Partition选举Leader。
4. 为Parition选举Leader的规则是：以Replica在ISR中存活为前提，按照Replica在AR中的顺序为优先级选择。假设Partition有3个Replica 0 1 2，它的AR是[1,0,2]，但是ISR是[0,2]。那么最终会选出Replica 0作为Leader。**这里以Replica 1为Leader举例**。
5. 选出Leader后，Broker0将这些信息告诉zk保管起来。接下来其他Broker的Controller模块会主动从zk获取Parition的主从信息。
6. Leader所在的Broker后，zk会监控到并更新/brokers/ids/的结构，当Broker0监听/brokers/ids/目录发现Leader挂了后，会获取ISR列表和AR列表重新选举Leader（重复第4步），然后更新Leader信息到zk，接着整个集群都会认为Replica 0是这个Partition的新Leader了。

**但是！！！！！！！！！！！！！！！！！！！！！！！！！！！！！！！如果集群的Controller挂掉了，又该谁去监听呢？**

## 20-Broker的服役与退役

新Broker连接上kafka集群后，默认是不会对Topic的Partition重分配的。有点像Redis的分片集群，新加节点后还要进行reshard操作。首先创建一个负载均衡文件reshard.json，指定要重分配的Topic：

```json
{
	“topics”:[
		{"topic"："hello"}
	],
	"version":1
}
```

然后执行重分配命令：

```bash
./kafka-reassign-partitions.sh --bootstrap-server 192.168.120.161:9092 --topics-to-move-json-file ${上面的文件名} --broker-list "0,1,2,3 （3是新增的Broker）" --generate
```

它会返回一个重分配计划，将计划粘贴到一个计划文件plan.json内，然后执行计划命令：

```bash
./kafka-reassign-partitions.sh --bootstrap-server 192.168.120.161:9092 --reassignment-json-file plan.json --execute
```

最后在看看集群的Parition分配：

```bash
./kafka-reassign-partitions.sh --bootstrap-server 192.168.120.161:9092 --reassignment-json-file plan.json --verify
```

**退役的过程和服役一样，只不过将重分配的topic在Broker上分布的数量减少（去掉要退役的节点），然后执行计划命令。**

# 21-Replica

1个Partition有1个或多个Replica，Replica被分为Leader和Follower。1个Parition默认派生1个Replica，这个Replica就是Leader，但没有Follower。在生产环境中为了保证数据可靠性，一般Parition会被设置派生2个Replica，对应1个主1个副。实际上，消息的发送和接收都是基于Leader进行的，太多的Follower虽然是加强了数据的可靠性，但也牺牲了一定的网络同步性能，所以1个Partition一般不会考虑设置太多的Replica。

1个Parition的所有Replica统称为AR（Assigned Replicas）。

1个Partition里，和Leader保持同步的Replica集合（包含Leader）被称为ISR（In Sync Replica）。如果Follower长时间未向Leader发出数据同步请求（由replica.lag.time.max.ms设置，默认30s），那么这个Follower会被移出ISR。

1个Parition里，和Leader同步超时，延迟过多的Replica集合被称为OSR。

假设ParitionA有3个Replica 0 1 2，其中0是Leader，1和2是Follower。这3个Replica都在正常工作着，那么Partition的AR是[0,1,2]，ISR是[0,1,2]，OSR是[]。

随着时间的推移，Replica2所在的Broker发生了意外宕机，导致Replica2超过30s未向Replica 0发起同步请求，那么Replica2就被踢出ISR列表，此时Partition的AR是[0,1,2]，ISR是[0,1]，AR是[2]。

**总的来说，AR = ISR + OR。**

# 故障转移后，消息的状态

## 22-LEO与HW

要了解故障转移后消息的状态，首先要明白2个概念：LEO（Log End Offset）和HW（High Watermark）。假设某个Partition的Replica分布如下图所示：

![image](https://user-images.githubusercontent.com/48977889/174529192-253537a4-faf3-4fd7-95a2-a1d37394ba9c.png)

LEO是针对每个ISR里的Replica来说的，代表这个Replica的最后一个offset，即offset下标值 + 1。

而HW是针对一个Partition的ISR里所有Replica来说的，代表所有Replica里最低的一位LEO。

如上图所示，这个Partition在Broker0 1 2所在的Replica的LEO分别是8、5、7。这个PartitionHW是5。**对于消费者来说，这个Partition里能见到的最大消息偏移量其实是HW。**

## 23-Follower故障

假设知识点22示例中，Broker2挂掉了，会进行以下步骤：

1. 将Broker2剔出ISR。此时Broker的LEO是7，整个Partition的HW是5

2. Broker1和Broker0继续同步数据。

3. 当Broker2恢复后，先从磁盘读取宕机前的HW是多少，发现是5。于是将5之后的数据全部清掉，并从5开始重新和Leader同步。

   ![image](https://user-images.githubusercontent.com/48977889/174530276-d76649d2-b949-48d8-87bb-0789aa03f16a.png)

4. 当Broker2发现自己的LEO ≥ Partition的HW（上图是8）后，就可以重新加入ISR了。

## 24-Leader故障

![image](https://user-images.githubusercontent.com/48977889/174529192-253537a4-faf3-4fd7-95a2-a1d37394ba9c.png)

还是以这张图为例，假如此时Leader挂了，会进行以下处理：

1. 根据知识点19的选举流程，选出一个新Leader，假设新Leader选择为Broker1

2. 此时Broker1作为新Leader，Broker2作为Follower需要拉取数据，但发现自己的LEO ＞ Leader的LEO，此时Broker2会将超出的部分清理掉，重新进行同步：

   ![image](https://user-images.githubusercontent.com/48977889/174530645-fae13d00-814f-4636-9a3f-bcb29aeac62e.png)

由此可见，当Leader挂掉后，故障转移只能保证Replica之间的数据一致性，但不能保证数据可靠性。**想要达到数据可靠性，还得基于知识点5的min.insync.replicas + ack=all的配置，保证所有Follower都同步了才ACK。**

## 25-故障转移后，Replica分配不均匀

# 消息存储

## 26-消息在文件系统上的存储

![image](https://github.com/assets/48977889/2b0f974f-c8ec-49c8-8d5d-ce9cc415e195)

1个Topic（逻辑）有多个Partition，1个Partition存储消息时，会落实到1个log（逻辑进行存储），1个逻辑log对应多个segment，1个segment包含3个文件:log文件，index文件，timeindex文件。**同一个Partitons的多个segment的文件都存储在1个文件夹里，这个文件夹的命名一般是topic名➕partition序号**。

看一下虚拟机上的文件结构：

```bash
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/data/Hello-0$ ll
总用量 12
-rw-r--r-- 1 kjg staff 10485760 6月  19 14:17 00000000000000000014.index
-rw-r--r-- 1 kjg staff        0 6月  19 10:46 00000000000000000014.log
-rw-r--r-- 1 kjg staff       10 6月  19 14:17 00000000000000000014.snapshot
-rw-r--r-- 1 kjg staff 10485756 6月  19 14:17 00000000000000000014.timeindex
-rw-r--r-- 1 kjg staff        4 6月  19 10:45 leader-epoch-checkpoint
-rw-r--r-- 1 kjg staff       43 5月  29 14:23 partition.metadata
kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/data/Hello-0$ pwd
/usr/local/kafka/kafka_2.12-3.0.0/data/Hello-0
```

1个segment的消息文件命名有点奇怪，比如上面的00000000000000000014，这代表着这个segment的第一条消息偏移量是14，消息文件都是以这种规则进行命名的，方便定位消息。

## 27-如何通过消息文件定位消息？

![image](https://github.com/assets/48977889/638bf205-b03c-42dc-986c-ad330fd03d50)

首先要明确2点：

1. kafka采用**稀疏索引**策略，每写入4KB的消息（通过log.index.interval.bytes配置），会往index文件里写入1条记录。
2. index文件只会存储**相对offset**和position。

假设我要定位offset = 600的消息：

1. 先通过600这个offset定位到消息在00000000000000000522.log这个文件里，那么就要找到00000000000000000522的index文件。因为522 < 600 < 1005。
2. 通过index文件存储的相对offset + 文件offset，定位到消息在相对offset = 65和117这2个索引之间。因为522 + 65 = 587，522 + 117 = 639，并且587 < 600 < 639。
3. 确定了消息在position = 6410 到 13795后，在log文件里找到position=6410的记录，从上往下遍历，就会找到offset = 600的消息。

假设我要定位offset = 523的消息：

1. 先通过600这个offset定位到消息在00000000000000000522.log这个文件里，那么就要找到00000000000000000522的index文件。因为522 < 523 < 1005。
2. 通过index文件存储的相对offset + 文件offset，定位到消息在相对offset 65的索引之前。因为522 + 65 = 587，并且523  < 587
3. 确定了消息在position = 6410之前，在log文件里找到position = 0的记录，从上往下遍历，就会找到offset = 523的消息。

## 28-消息清除策略

kafka虽然提供了消息持久化，但消息总不能一直持久化在硬盘上，因此kafka有一套持久化消息的清除策略。默认情况下，kafka的消息保存时间为7天，超过这个时间会启动清除策略，那通过什么来判断消息超过7天呢？其实是通过timeindex文件。 

当然，消息保存时间也是可以通过配置修改：

1. log.retention.hours：保留小时，默认是168，即7天。
2. log.retention.minutes：保留分钟。
3. log.retention.ms：保留毫秒。
4. log.retention.check.interval.ms：通过这个配置来轮询，查看消息是否超时，默认是5分钟。
5. 如果同时设置了小时和分钟，会采用哪种呢？答案是：**时间范围越细，优先级越高，即采用分钟。**

对于过期消息，主要有两种处理方式：

1. 删除

   通过log.cleanup.policy=delete设置，以segment的timeindex文件里的**最大时间戳为准**，如果（最大时间戳 到现在的时间间隔） ＞ 时间阈值，则会删除对应segment的消息文件。所谓最大时间戳，如果这个segment有部分消息还是新的，那么就不考虑进删除范围里，如下图的segment2：

   ![image](https://github.com/assets/48977889/1f97f205-57d5-4fbb-bedd-0791bd4fbe2f)

2. 压缩：

   通过log.cleanup.policy=compact设置，将过期消息里相同Key的消息进行压缩，只保留最后一个版本（**注意！！！这里同Key不同Value的消息之间本质是不同的消息，只是Key相同**），这样之前的版本就丢弃了，仅保留最新的消息：

   ![image](https://github.com/assets/48977889/bd36a988-3d7b-4f0e-b0a0-26718845235d)

   进行压缩后可以看到，消息连同offset一并删除了，如果消费者此时消费offset = 6的数据，会直接消费offset = 7的消息。

# Broker的高效读与高效写

## 29-高效写

消费者在获取消息时Broker采用稀疏索引的方式定位，也加快了消息的消费速度。除此之外，Kafka采用了**顺序写磁盘**的方案，对Producer写到Partition的消息会写入log文件中，并且后续的写入是不停追加到log文件末端，省去了随机写的磁头寻址时间。并且顺序写还保证了一个特性：消费是根据先来后到依次写入磁盘（知识点17）中，因此一个offset就能快速定位消息的位置。

## 30-高效读

Kafka采用页缓存+零拷贝的技术向消费者发送消息。对于Broker在应用层根本不关心数据长什么样，也不会对数据进行处理，**数据的处理依赖Producer和Consumer的拦截器、序列化器、反序列化器（知识点9）**。因此对于Broker来说可以直接将内核空间里的消息直接transferTo网卡，发送给Consumer:

![img](https://user-images.githubusercontent.com/48977889/174797988-fd4cae32-edde-4e9e-a319-231c9fd3f408.png)

左图是如果不使用零拷贝技术的场景：

1. Broker从内核态缓存Page Cache获取消息，如果获取不到，则先从磁盘读取消息到内核态缓存。
2. Broker将内核态缓存的消息copy到用户态空间。
3. Broker将用户态空间的消息拷贝到内核态缓存Socket Cache。
4. Broker将3.操作后，处于Socket Cache的消息发送到网卡，最终发给Consumer

但是上面都说了，Broker不会参与消息的处理，那还要拷贝一次到用户态干嘛呢？

所以直接采用右图的步骤：

1. Broker从内核态缓存Page Cache获取消息，如果获取不到，则先从磁盘读取消息到内核态缓存。
2. 调用transferTo，将内核态缓存中的消息直接发给Consumer。

**这一点和NIO使用的零拷贝是一样的**