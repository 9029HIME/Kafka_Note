# Zookeeper是什么

在两年前我刚接触分布式的时候，就听到Zookeeper这个名字，当时网络比较难找到规范的文章，官方文档的概述也让我难以理解，给我最大的感受是zookeeper是分布式的，并且很多其他的分布式组件会依赖使用它。虽然Kafka2.8开始可以不再依赖Zookeeper，但作为知识面的扩展，还是有必要系统地学习一下它。

Zookeeper其实是一个分布式的、为分布式框架提供协调服务的项目，注意两个关键：为其他分布式框架服务，提供协调服务，Zookeeper提供的协调服务有什么？首先了解以下Zookeeper的工作模式。

Zookeeper是基于观察者设计模式的分布式服务管理框架，它负责存储和管理**服务生产者**的信息，将这些信息提供给**服务消费者**。当生产者的信息发生了变化，Zookeeper也负责通知消费者这些变化让消费者做出相应的反应，生产者和消费者都需要注册到Zookeeper里才能使用Zookeeper的功能。往大了看，Zookeeper本质就是一个**有通知机制的分布式文件系统**：

![0cae84f5b190b89c96515502fae058a](https://user-images.githubusercontent.com/48977889/168458819-9e02af20-5845-4967-ada0-2c616d882d7f.jpg)

# Zookeeper特点

![截图_选择区域_20220515140013](https://user-images.githubusercontent.com/48977889/168459844-3ca852d1-ffa0-4eca-b38a-d28b02dcf6da.png)

1. 一个Zookeeper集群有1个Leader和多个Follower组成。
2. 集群中需要有**半数以上（大于半数）**的Zookeeper节点存活，Zookeeper集群才能正常服务，所以Zookeeper集群适合安装奇数台节点。假设1个集群有5个节点，挂了3台就整个集群就宕机了，如果这个集群部署了6台节点，在6台节点的基础上挂3台也会导致宕机，那还不如直接部署5台。
3. 全局数据一致，Follower和Leader的数据在一个时间段内是一致的。
4. 数据更新原子性：一次数据要么全部成功，要么全部失败，这和MySQL的事务概念类似。

# Zookeeper安装（本机）

1. 解压tar包。
2. 将conf/下的zoo_sample.cfg改名为zoo.cfg。
3. 新建一个data目录。
4. 将zoo.cfg内的dataDIr项改为3.新建的data目录。
5. 到bin目录下执行zkServer.sh脚本，指定参数为“start”，启动zookeeper。

# Zookeeper配置

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





