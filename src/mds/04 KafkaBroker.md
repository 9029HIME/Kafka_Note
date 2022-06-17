# 18-Kafka在ZK上存储的信息

虽然kafka3.x后不再依赖ZK管理元数据了，但还是要了解一下ZK模式的数据存储的。

## 19-Broker总流程

Broker是没有主从概念的，Parition才有主从概念。

## 20-Broker的服役与退役

新Broker连接上kafka集群后，默认是不会对Topic的Partition重分配的。有点像Redis的分片集群，新加节点后还要进行reshard操作。首先创建一个负载均衡文件reshard.json，指定要重分配的Topic：



然后执行重分配命令：



它会返回一个重分配计划，将计划粘贴到一个计划文件plan.json内，然后执行计划命令：

**退役的过程和服役一样，只不过将重分配的topic在Broker上分布的数量减少（去掉要退役的节点），然后执行计划命令。**

# 21-Replica

1个Partition有1个或多个Replica，Replica被分为Leader和Follower。

ISR

OSR

AR