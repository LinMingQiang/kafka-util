package com.kafka.offset.record

import com.kafka.zk.util.ZookeeperUtil
import com.kafka.zk.util.KafkaUtil
import kafka.common.TopicAndPartition
import org.slf4j.LoggerFactory
import kafka.admin.AdminUtils
import kafka.common.TopicAndPartition
import kafka.utils.ZkUtils
/**
 * @func ： 对外的工具类，提供各类的kafka操作
 * @author LMQ
 * @time  2018-04-13
 */
private[kafka] class KafkaOffsetUtil(
    val kafkaParams: Map[String, String],
    val zk: String) {
  val zkUtil = new ZookeeperUtil(zk)
  val kafkautil = new KafkaUtil(kafkaParams)
  val groupid = if (kafkaParams.contains("group.id")) kafkaParams.get("group.id").get else "kafkadayoffset"
  val LOG = LoggerFactory.getLogger("KafkaOffsetUtil")
  val consumerPath = s"""/consumers/${groupid}"""
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：将当天的offset记录到zk
   * 如parentPath=/consumer/groupid/20180413
   * 对应的结果是
   * /consumer/groupid/20180413/topicname/partition/offset
   */
  def recordDayOffsetsToZK(
    day: String,
    topics: Set[String],
    overWrite: Boolean = true): Either[String, Map[TopicAndPartition, Long]] = {
    val offsetPath = consumerPath + "/" + day
    val recordTopic = if (topics == null || topics.isEmpty) {
      val alltopics = getAlltopics.toSet
      LOG.warn("Topics Is Null : ", alltopics.mkString(","))
      alltopics
    } else topics
    zkUtil.createMultistagePath(offsetPath)
    val offsets = recordTopic.flatMap { topic =>
      val topicsOffset = kafkautil.getLatestLeaderOffsets(Set(topic), zkUtil.zkClient)
      val result = recordOffsetToZK(topic, topicsOffset, offsetPath, overWrite)
      if (result.isLeft) {
        return new Left(result.left.get)
      }
      topicsOffset
    }.toMap
    new Right(offsets)
  }

  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：将当天的offset记录到zk
   * 如parentPath=/consumer/groupid/20180413/18
   * 对应的结果是
   * /consumer/groupid/20180413/18/topicname/partition/offset
   */
  def recordDayHourOffsetToZK(
    day: String,
    hour: String,
    topics: Set[String],
    overWrite: Boolean = true   //如果存在了是否覆盖重写
    ): Either[String, Boolean] = {
    val offsetPath = consumerPath + "/" + day + "/" + hour
    val recordTopic = if (topics == null || topics.isEmpty) {
      val alltopics = getAlltopics.toSet
      LOG.warn("Topics Is Null : " + alltopics.mkString(","))
      alltopics
    } else topics
    zkUtil.createMultistagePath(offsetPath)
    recordTopic.foreach { topic =>
      val topicsOffset = kafkautil.getLatestLeaderOffsets(Set(topic), zkUtil.zkClient)
      val result = recordOffsetToZK(topic, topicsOffset, offsetPath, overWrite)
      if (result.isLeft) {
        return result
      }
    }
    new Right(true)

  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：将offset记录到zk
   * 如parentPath=/consumer/groupid
   * 对应的结果是
   * /consumer/groupid/topicname/partition/offset
   */
  private def recordOffsetToZK(
    topic: String,
    offset: Map[TopicAndPartition, Long],
    path: String,
    overWrite: Boolean = true): Either[String, Boolean] = {
    val topicPath = s"""${path}/${topic}"""
    val result = zkUtil.createFileOrDir(topicPath, "")
    if (result.isRight) {
      offset.foreach {
        case (tp, offset) =>
          val path = s"""${topicPath}/${tp.partition}"""
          if(!zkUtil.isExist(path) || overWrite){
            try { zkUtil.writeData(path, offset.toString) }
              catch {
                case t: Throwable =>
                  LOG.error(t.toString())
                  return new Left(t.toString() + "\n" + t.getStackTraceString)
              }
          }else {
            LOG.info("PATH ISEXIST : "+path)
          }
      }
      result
    } else result

  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取某一天，某个topic的offset
   */
  def getDayOffsetsFromZK(
    day: String,
    topics: Set[String],
    parentPath: String = consumerPath): Either[String, Map[TopicAndPartition, Long]] = {
    val readTopic = if (topics == null || topics.isEmpty) {
      val alltopics = getAlltopics.toSet
      LOG.warn("Topics Is Null : " + alltopics.mkString(","))
      alltopics
    } else topics
    val topicOffsets = readTopic.flatMap { topic =>
      val topicOffset = getDayOffsetFromZK(topic, day, parentPath)
      if (topicOffset.isLeft) {
        return topicOffset
      } else topicOffset.right.get
    }.toMap
    new Right(topicOffsets)
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取某一天，某个topic的offset
   */
  def getDayHourOffsetsFromZK(
		topics: Set[String],
    day: String,
    hour: String,
    parentPath: String = consumerPath): Either[String, Map[TopicAndPartition, Long]] = {
    val topicOffsets = topics.flatMap { topic =>
      val topicOffset = getDayHourOffsetFromZK(topic, day, hour, parentPath)
      if (topicOffset.isLeft) {
        return topicOffset
      } else topicOffset.right.get
    }.toMap
    new Right(topicOffsets)
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取某一天，某个topic的offset
   */
  private def getDayOffsetFromZK(
    topic: String,
    day: String,
    parentPath: String = consumerPath): Either[String, Map[TopicAndPartition, Long]] = {
    val topicPath = consumerPath + "/" + day + "/" + topic
    if (zkUtil.isExist(topicPath)) {
      val topicAndPart = kafkautil.getTopicAndPartitions(Set(topic))
      val map = topicAndPart.map { tp =>
        val partpath = s"""${topicPath}/${tp.partition}"""
        if (zkUtil.isExist(topicPath)) {
          (tp -> zkUtil.readData(partpath).toLong)
        } else return new Left("Part Path Not Exist : " + partpath)
      }
      new Right(map.toMap)
    } else new Left("Topic Path Not Exist : " + topicPath)
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取某一天，某个topic的offset
   */
  private def getDayHourOffsetFromZK(
    topic: String,
    day: String,
    hour: String,
    parentPath: String = consumerPath): Either[String, Map[TopicAndPartition, Long]] = {
    val topicPath = consumerPath + "/" + day + "/" + hour + "/" + topic
    if (zkUtil.isExist(topicPath)) {
      val topicAndPart = kafkautil.getTopicAndPartitions(Set(topic))
      val map = topicAndPart.map { tp =>
        val partpath = s"""${topicPath}/${tp.partition}"""
        if (zkUtil.isExist(topicPath)) {
          (tp -> zkUtil.readData(partpath).toLong)
        } else return new Left("Part Path Not Exist : " + partpath)
      }
      new Right(map.toMap)
    } else new Left("Topic Path Not Exist : " + topicPath)
  }
  /**
   * @func 获取某天的offset的路径
   */
  def getDayOffsetPath(
      day:String,
      parentPath: String = consumerPath)={
    consumerPath + "/" + day
  }
  /**
   * @func 获取某天某小时的offset的路径
   */
  def getDayHourOffsetPath(
      day:String,
      hour:String,
      parentPath: String = consumerPath)={
    consumerPath + "/" + day + "/" + hour
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取所有的 topic name
   */
  def getAlltopics() = {
    ZkUtils.getAllTopics(zkUtil.zkClient)
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：判断某目录是否存在
   */
  def isExist(path: String) = {
    zkUtil.isExist(path)
  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：删除某个路径
   */
  def deletePath(path: String) = {
    zkUtil.deletePath(path)
  }
   /**
   * @author LMQ
   * @time 2018-04-13
   * @func：递归删除某个路径
   */
  def deleteRecursive(path: String) = {
    zkUtil.deleteRecursive(path)
  }
    /**
   * @author LMQ
   * @time 2018-04-13
   * @func：创建文件
   * overWrite : 如果存在是否覆盖
   */
  def createFileOrDir(
    path: String,
    data: String,
    overWrite: Boolean = false) = {
    if (zkUtil.isExist(path)) {
      if (overWrite) {
        zkUtil.deletePath(path)
        zkUtil.createFileOrDir(path, data)
      }
    } else zkUtil.createFileOrDir(path, data)

  }
  /**
   * @author LMQ
   * @time 2018-04-13
   * @func：获取topic 的 最新  offset
   */
  def getTopicLastOffset(topics: Set[String]) = kafkautil.getLatestLeaderOffsets(topics, zkUtil.zkClient)

}
object KafkaOffsetUtil {
  def apply(kafkaParams: Map[String, String], zk: String) = new KafkaOffsetUtil(kafkaParams, zk)
}
