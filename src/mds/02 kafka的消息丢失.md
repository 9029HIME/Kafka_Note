# 4-消息传递语义

kafka作为一个Producer-Broker-Consumer架构的消息队列，丢失消息的情况主要发生在

1. Producer → Broker
2. Broker自身处理过程
3. Consumer ← Broker

这三种场景上，在了解消息丢失的细节前，先了解Kafka的一个关键概念：消息传递语义。它代表着消息在Kafka上传递的可靠性：

1. at most once：消息可能会丢失也可能会被处理，但最多只处理一次。
2. at least once：至少一次，消息不会丢失，但可能会重复发送，导致被处理多次。
3. exactly once：消息不会丢，也不会重复发送，消息只会被处理一次。

当然，消息传递语义在kafka里更多是一种思想或规则。**实现消息传递语义的实现需要贯穿Producer、Broker、Consumer的配置，而不是通过一个简单的“消息传递语义”配置来完成的。**

# 5-Producer到Broker

![cbb5d1f0ef7f4befb986e4eb6b5e42b](https://user-images.githubusercontent.com/48977889/169038097-f42ca1d6-be45-4afa-8ba2-755a35bf4519.png)以下是Kafka在Producer消息传递过程中一个**最全面的、完整**的流程（但是通过配置省略部分操作）

1. Producer获取Topic的Leader信息（如所在Broker，端口等）。
2. Producer将消息发给Leader。
3. Broker接受到消息后
   1. Broker将消息写到系统Cache。
   2. 系统Cache的消息被刷盘到disk。
   3. ISR的Follower主动拉取消息，进行同步（只有被刷盘的消息才能被Follower同步）。
   4. Follower同步消息完毕，给Leader一个ACK。
4. Broker给Producer一个ACK，告诉它消息接受成功。

对于Producer确认消息是否成功传送的过程中，**可以在Producer**通过request.required.acks进行3种配置：

1. request.required.acks=0：只管发，不关心Broker是否给出ACK。处理步骤：1、2。
2. request.required.acks=1：Broker将消息持久化后，就返回ACK，不需要等Follower同步好消息。处理步骤：1、2、3.1、3.2、4。
3. request.required.acks=-1或者all：Broker将消息持久化后，还需要等min.insync.replicas个follower同步完消息，再返回ACK。处理步骤：1、2、3.1、3.2、3.3、3.4、4。

当消息在Producer到Broker的过程中，可能会出现以下2种丢失情况：

1. 如果acks=0，因网络故障没有发送到Leader处，消息就丢了。
2. 如果acks=1，消息到了Leader那，Leader写入Cache、给Producer回复了ACK后，Leader挂了。此时选举出来的新Leader却没有同步到这个消息，消息就丢了。

也可能会出现以下2种重发情况：

1. 如果acks=1，Leader回复了ACK，但由于网络故障Producer在规定时间内没有收到ACK，于是向Leader重新发送消息。
2. 如果acks=1或者all，Leader给Producer回复ACK，但由于网络故障Producer在规定时间内没有收到ACK，于是向Leader重新发送消息。

# 6-Producer发送消息前

对于Producer发送到Broker的过程中，有同步和异步两种方式。通过producer.type这个配置确定，默认情况下使用sync，也就是同步方式。其实同步方式和异步方式是针对**业务线程**来说的，同步方式下，业务线程需要等待broker的返回结果（具体看知识点5的ack策略）后，发送行为才结束，**在发送行为结束之前业务线程不会再往InFlightRequest队列里推消息**。

而异步模式下，**业务线程**发送消息其实是将消息推到InFlightRequest队列里，这个队列可以理解为buffer。sender线程会根据**特定规则**将队列里的数据分批次发给broker，当然，这一批次的ack也会通过回调的方式进行处理，也就是说知识点5的ack和这里的异步是不冲突的。

那么说到**特定规则**，可以通过4个参数配置：

1. queue.buffering.max.ms：消息在队列中停留的最大时间，超过这个时间sender会将消息发送出去。
2. queue.buffering.max.messages：队列里缓存的消息最大个数，超过这个值后，producer会**阻塞等待队列空闲或直接丢掉这个消息**。
3. queue.enqueue.timeout.ms：2.的阻塞时间，超过这个时间直接丢。0代表不等待直接丢，-1代表一直阻塞。
4. batch.num.messages：批次大小，当队列里的消息超过这个大小，sender会将批次大小的消息发出去。

其实1.和4.并不冲突，如果消息量很小的情况下一直没达到批次大小，sender岂不是一直不发消息？所以需要queue.buffering.max.ms进行兜底，时间一到不管有多少，都发送出去（最大数量是批次大小）。

回到消息丢失问题，其实异步发送很有可能导致消息丢失的发生，毕竟消息在发送前存在producer的队列里，在这个时候一旦producer挂了，这个消息也就丢掉了。又或者，消息的生产速度＞消费速度导致队列满了，在配置2.的情况下有可能会被直接丢掉。即使不丢失采取-1配置一直阻塞等待，导致后面的线程一同阻塞，线程数累计起来直接OOM，结果还是会丢。

# 7-消息到了Broker后

结合知识点5可以看到，Broker接受到消息后，根据Producer要求的ack机制会有不同的处理方式。对于KafkaBroker来说，消息持久化只是将消息写入系统缓存中，具体什么时候被刷到硬盘是交给OS来处理的。一般来说刷盘有3种触发点：

1. 主动调用sync或fsync函数。
2. 可用缓存低于阈值。
3. 缓存内的数据存在时间达到阈值。

可是Kafka和RocketMQ不一样，它不会主动调用函数刷盘，只能依靠OS根据2.和3.将消息刷到磁盘里。也就是对于KafkaBroker来说，只能调整刷盘参数2.和3.来缓解尽可能保证持久化。虽然Broker不能确保不丢，但结合知识点5可以知道，还是能配合Producer来保证消息的可靠性。

在ack=1的情况下，Broker仅保证消息写入Cache后给Producer一个ACK，之后Broker宕机，新Leader也没有同步到消息的话这个消息就丢了，毕竟Producer认为消息已达，没必要重发。需要知道的是，消息在Leader被写入disk后，才会被其他Follower同步到，在ack=-1或all的情况下，需要等min.insync.replicas个Follower同步好消息才会返回ACK。也就是说，这时候Producer收到ACK就代表**最起码消息在Leader的磁盘是存在的，并且有一定数量的Follower也同步到信息**，即使这时候Leader宕机了，其他Follower顶上还是能保证消息可靠性。

# 8-消息被Consumer拉取后

要知道Kafka架构中消费者是Pull方式获取消息，获取到消息后无非就两种处理方式：先消费，再commit。先commit，再消费。后者比较危险，如果commit后消费逻辑发生了异常，这条消息就丢掉了。所以最保险的做法还是先消费再commit，虽然在消费完commit前消费者宕机的话会导致重复消费，但这种属于极端情况，这种情况下可以通过幂等方式避免重复消费。

# 总结一下

总的来说，在实际的业务场景下，100%保证kafka消息不丢失还是比较困难的。作为开发工程师应在业务场景与丢失率做出平衡，并且与产品经理、高级研发确定好消息丢失的兜底方案。

