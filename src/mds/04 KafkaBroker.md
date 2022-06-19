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
6. 当Broker0监听到这个Leader所在的Broker挂了后，会获取ISR列表和AR列表重新选举Leader（重复第4步），然后更新Leader信息到zk，接着整个集群都会认为Replica 0是这个Partition的新Leader了。

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