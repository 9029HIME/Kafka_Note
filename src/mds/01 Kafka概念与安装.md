# Kafka关键概念

以下是Kafka整体架构图（基于3.x）

![eefc416bfa8abf7a652c09683c03050](https://user-images.githubusercontent.com/48977889/167411175-3fc284d2-1dde-4d76-a2d2-f75ad2d8c208.jpg)

1. broker的概念和RabbitMQ一样。
2. Topic是区分消息的一个属性。
3. 1个Partition只能被一个consumer消费，防止重复消费，partition是基于topic划分的。
4. leader和follower，不管是生产还是消费，都只针对leader。
5. kafka会通过zookeeper记录每一个broker的状态信息，每一个partition的信息，有点类似于kafka的注册中心，不过在3.x之后zookeeper就不是必须组件了，可以直接用kafka的craft模式完成。

# Kafka安装