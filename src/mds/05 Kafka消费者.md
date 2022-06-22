# 31-消息的消费总流程

![img](https://user-images.githubusercontent.com/48977889/174946914-56e1d9a3-ddb7-4409-b8de-6afaab9bf820.png)

首先要明确一点：1个Partition不能被**同组的多个Consumer**消费，但是1个Consumer可以消费多个Partition。

Consumer对某个Partition的消费进度（offset），是保存在ZK里的，**当然新版本是维护在Broker的__consumer_offsets里，目的是减少Consumer和ZK进行冗余的IO交互。**

Consumer是必须要指定消费者组的，即使是1个单独的Consumer，它都会有1个默认的消费者组。结合上面的说法，其实是1个Partition是可以被多个消费者组消费的：

![img](https://user-images.githubusercontent.com/48977889/174947804-04e02e1a-4e7c-4b02-be7c-69e7270e5781.png)

# 32-消费者组初始化流程

# 33-消费者消费过程

# 消费者消费实例代码

## 34-消费某个主题

## 35-消费某个Partition

## 36-消费者组消费某个主题



# 分区分配与再平衡

在Consumer可以配置

Range：再平衡后，直接将宕机消费者的消费分区移到其他存活的消费者上。