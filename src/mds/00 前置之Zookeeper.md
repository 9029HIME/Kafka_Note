# 1-Zookeeper是什么

在两年前我刚接触分布式的时候，就听到Zookeeper这个名字，当时网络比较难找到规范的文章，官方文档的概述也让我难以理解，给我最大的感受是zookeeper是分布式的，并且很多其他的分布式组件会依赖使用它。虽然Kafka2.8开始可以不再依赖Zookeeper，但作为知识面的扩展，还是有必要系统地学习一下它。

Zookeeper其实是一个分布式的、为分布式框架提供协调服务的项目，注意两个关键：为其他分布式框架服务，提供协调服务，Zookeeper提供的协调服务有什么？首先了解以下Zookeeper的工作模式。

Zookeeper是基于观察者设计模式的分布式服务管理框架，它负责存储和管理**服务生产者**的信息，将这些信息提供给**服务消费者**。当生产者的信息发生了变化，Zookeeper也负责通知消费者这些变化让消费者做出相应的反应，生产者和消费者都需要注册到Zookeeper里才能使用Zookeeper的功能。往大了看，Zookeeper本质就是一个**有通知机制的分布式文件系统**：

![0cae84f5b190b89c96515502fae058a](https://user-images.githubusercontent.com/48977889/168458819-9e02af20-5845-4967-ada0-2c616d882d7f.jpg)

# 2-Zookeeper特点

![截图_选择区域_20220515140013](https://user-images.githubusercontent.com/48977889/168459844-3ca852d1-ffa0-4eca-b38a-d28b02dcf6da.png)

1. 一个Zookeeper集群有1个Leader和多个Follower组成。
2. 集群中需要有**半数以上（大于半数）**的Zookeeper节点存活，Zookeeper集群才能正常服务，所以Zookeeper集群适合安装奇数台节点。假设1个集群有5个节点，挂了3台就整个集群就宕机了，如果这个集群部署了6台节点，在6台节点的基础上挂3台也会导致宕机，那还不如直接部署5台。
3. 全局数据一致，Follower和Leader的数据在一个时间段内是一致的。
4. 数据更新原子性：一次数据要么全部成功，要么全部失败，这和MySQL的事务概念类似。

# 3-Zookeeper安装（本机）

1. 解压tar包。
2. 将conf/下的zoo_sample.cfg改名为zoo.cfg。
3. 新建一个data目录。
4. 将zoo.cfg内的dataDIr项改为3.新建的data目录。
5. 到bin目录下执行zkServer.sh脚本，指定参数为“start”，启动zookeeper。

# 4-Zookeeper配置

```properties
# Zookeeper集群内，节点与节点之间、节点与client之间的心跳时间，单位为ms。
tickTime=2000
# Leader和Follower第1次建立连接的时候，最多能忍受多少次心跳，如果超过这个次数还未建立连接，则认为通讯失败。
initLimit=10
# Leader和Follower第1+n次建立连接的时候，最多能忍受的心跳数。
syncLimit=5
# 存储Zookeeper数据的路径。
dataDir=/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/data
# Zookeeper暴露的端口号。
clientPort=2181
# the maximum number of client connections.
# increase this if you need to handle more clients
#maxClientCnxns=60
#
# Be sure to read the maintenance section of the 
# administrator guide before turning on autopurge.
#
# http://zookeeper.apache.org/doc/current/zookeeperAdmin.html#sc_maintenance
#
# The number of snapshots to retain in dataDir
#autopurge.snapRetainCount=3
# Purge task interval in hours
# Set to "0" to disable auto purge feature
#autopurge.purgeInterval=1
```

# 5-Zookeeper安装（集群）

1. 准备好了其他2台虚拟机Ubuntu01和Ubuntu02，先分别按照知识点3那样本地安装Zookeeper。

2. 在各自主机的dataDir目录下，新建一个myid文件，里面只需要填1个数字，不能有换行，空格。这个数字代表这个主机在zookeeper集群中的唯一标识：

   ```bash
   kjg@kjg-PC:~$ cd /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/data/
   kjg@kjg-PC:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/data$ vim myid
   ```

   ```bash
   kjg1@ubuntu01:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin$ cd data
   kjg1@ubuntu01:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/data$ vim myid
   ```

   ```bash
   kjg1@ubuntu02:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin$ cd data/
   kjg1@ubuntu02:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/data$ vim myid
   ```

3. 此时需要在3个主机的配置文件都加上集群配置，集群配置的格式如下：

   server.A=B:C:D

   A：在2.标识的myid。

   B：主机能被对外访问的ip或域名。

   C：Leader和Follower同步信息的端口。

   D：Leader宕机后，通过这个端口来进行集群内的重新选举。

   这里采用如下配置：

   ```properties
   server.1=192.168.120.161:2888:3888
   server.2=192.168.120.121:2888:3888
   server.3=192.168.120.122:2888:3888
   ```

   注意，需要集群内所有主机的zookeeper配置都加上这个。

4. 启动三台机子的zookeeper

   ```bash
   kjg@kjg-PC:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh start
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Starting zookeeper ... STARTED
   kjg@kjg-PC:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh status
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Client port found: 2181. Client address: localhost.
   Mode: follower
   kjg@kjg-PC:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$
   ```

   ```bash
   kjg1@ubuntu01:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh start
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Starting zookeeper ... STARTED
   kjg1@ubuntu01:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh status
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Client port found: 2181. Client address: localhost.
   Mode: leader
   kjg1@ubuntu01:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ 
   ```

   ```bash
   kjg1@ubuntu02:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh start
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Starting zookeeper ... STARTED
   kjg1@ubuntu02:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ ./zkServer.sh status
   ZooKeeper JMX enabled by default
   Using config: /usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin/../conf/zoo.cfg
   Client port found: 2181. Client address: localhost.
   Mode: follower
   kjg1@ubuntu02:/usr/local/zookeeper/apache-zookeeper-3.5.7-bin/bin$ 
   ```

   可以看到ubuntu01被选举为Leader了。

