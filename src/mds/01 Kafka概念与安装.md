# 1-Kafka关键概念

以下是Kafka整体架构图（基于3.x）

![eefc416bfa8abf7a652c09683c03050](https://user-images.githubusercontent.com/48977889/167411175-3fc284d2-1dde-4d76-a2d2-f75ad2d8c208.jpg)

1. broker的概念和RabbitMQ一样。
2. Topic是区分消息的一个属性。
3. 1个Partition只能被一个consumer消费，防止重复消费，partition是基于topic划分的。
4. leader和follower，不管是生产还是消费，都只针对leader。
5. kafka会通过zookeeper记录每一个broker的状态信息，每一个partition的信息，有点类似于kafka的注册中心，不过在3.x之后zookeeper就不是必须组件了，可以直接用kafka的craft模式完成。

# 2-Kafka安装

1. 基于00-05知识点的3台主机，安装kafka，首先3台机子都解压kafka的tar包，这里下载的是3.0.0版本。

2. 在各自主机的kafka配置文件server.properties里配好broker.id，这是kafka在集群中的唯一标识，有点类似zookeeper的myid。

3. 在各自主机的kafka配置文件server.properties里配好log.dirs，我这里配的是/usr/local/kafka/kafka_2.12-3.0.0/data

4. 在各自主机的kafka配置文件server.properties里配好zookeeper.connect，使用00-05知识点配置好的zookeeper集群，并且创建好kafka在zookeeper的目录：

   zookeeper.connect=192.168.121.161:2181,192.168.121.121:2181,192.168.121.122:2181/kafka

5. 添加环境变量KAFKA_HOME和修改PATH变量，重新加载环境变量。

6. 执行3台主机的kafka启动脚本，指定-dameon参数，指定配置文件位置。

   ```bash
   kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-server-start.sh -daemon ../config/server.properties 
   kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ jps
   16944 Jps
   10418 QuorumPeerMain
   5003 Main
   16910 Kafka
   kjg@kjg-PC:/usr/local/kafka/kafka_2.12-3.0.0/bin$ 
   ```

   ```bash
   kjg1@ubuntu01:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-server-start.sh -daemon ../config/server.properties
   kjg1@ubuntu01:/usr/local/kafka/kafka_2.12-3.0.0/bin$ jps -l
   32503 org.apache.zookeeper.server.quorum.QuorumPeerMain
   34167 kafka.Kafka
   34190 sun.tools.jps.Jps
   kjg1@ubuntu01:/usr/local/kafka/kafka_2.12-3.0.0/bin$ 
   ```

   ```bash
   kjg1@ubuntu02:/usr/local/kafka/kafka_2.12-3.0.0/bin$ ./kafka-server-start.sh -daemon ../config/server.properties
   kjg1@ubuntu02:/usr/local/kafka/kafka_2.12-3.0.0/bin$ jps -l
   9761 sun.tools.jps.Jps
   8957 org.apache.zookeeper.server.quorum.QuorumPeerMain
   9742 kafka.Kafka
   kjg1@ubuntu02:/usr/local/kafka/kafka_2.12-3.0.0/bin$ 
   ```

   此时在ZK查询以下根目录，可以发现生成了kafka目录：

   ```bash
   [zk: localhost:2181(CONNECTED) 0] ls /
   [zookeeper]
   [zk: localhost:2181(CONNECTED) 1] 
   ```

# Kafka命令操作

对于kafka服务端来说，可以通过producer.sh、topic.sh、consumer.sh分别操作kafka的生产者、broker、消费者。

## 3-Topic操作

通过bin目录下的kafka-topics.sh操作，可以对topic进行增删查改操作，基本命令如下：

./kafka-topics.sh --bootstrap-server ${broker1}${broker2} -topic ${topic名称} --操作选项

一个一个来，首先是broker的地址，可以填多个，如果其中一个节点挂掉了还能访问另一个节点。

其次是操作选项，有以下选项：

1. create：创建
2. delete：删除
3. alter：修改
4. list：查看所有主题
5. describe：查看主题的详情
6. partitions：设置这个主题的分片数，**只可增加不可减少**
7. replication-factor：设置副本数，通过命令行无法减少

新建一个topic叫“HelloWorld”