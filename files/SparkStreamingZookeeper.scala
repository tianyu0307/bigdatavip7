import java.io.IOException
import java.util
import java.util.Properties
import kafka.utils.{ZkUtils, ZKGroupTopicDirs}
import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, RowFactory, SQLContext, SparkSession}
import org.apache.spark.sql.types._
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, KafkaUtils}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.log4j.Logger
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferBrokers
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe

import scala.util.Try

/**
  * Created by shuijun on 2018/10/16.
  */
object Test extends Serializable {


  val zkHosts = "localhost:2181"
  val ZK_SESSION_TIMEOUT = 60000
  val ZK_CONNECTION_TIMEOUT  = 60000

  val (zkClient, zkConnection) = ZkUtils.createZkClientAndConnection(zkHosts, ZK_SESSION_TIMEOUT, ZK_CONNECTION_TIMEOUT)
  //ZkUtils对象，用于访问Zookeeper
  val zkUtils = new ZkUtils(zkClient, zkConnection, false)

  val servers = "localhost:9092".toArray.mkString(",") // bootstrap.servers

  val params = Map[String, Object](
    "bootstrap.servers" -> servers,
    "key.deserializer" -> classOf[StringDeserializer],
    "value.deserializer" -> classOf[StringDeserializer],
    "auto.offset.reset" -> "latest",
    "group.id" -> "test",
    "enable.auto.commit" -> (false: java.lang.Boolean)
  )

  private val logger = Logger.getLogger(getClass)

  def main(args: Array[String]) {
    val spark = SparkSession.builder
      .appName("test-window")
      .master("local[*]")
      .getOrCreate

    val streaming = new StreamingContext(spark.sparkContext, Seconds(60))


    val topics = Array("test")
    val stream = KafkaUtils.createDirectStream[String, String](streaming, PreferBrokers, Subscribe[String,String](topics,params))

    val url = "jdbc:MySQL://localhost:3306/test"

    val table ="test"


    val structFields = new util.ArrayList[StructField]()
    structFields.add(DataTypes.createStructField("name", DataTypes.StringType, true));
    structFields.add(DataTypes.createStructField("age", DataTypes.IntegerType, true));
    val structType = DataTypes.createStructType(structFields)


    val props = new Properties
    props.setProperty("driver", "com.mysql.jdbc.Driver")
    props.setProperty("user", "")
    props.setProperty("password", "")

    // 别名
    type Record = ConsumerRecord[String, String]

    stream.foreachRDD((rdd: RDD[Record]) => {
      val pairs = rdd
        .map(row => (jsonDecode(row.value())))
        .map(row => {
          val result = RowFactory.create(row.getName, row.getAge)
        result
        })

     val df =  spark.createDataFrame(pairs,structType)
      df.createTempView("person")
      val data = spark.sql("select * from person")
      data.write.mode(SaveMode.Append).jdbc("","",props)

      //将offset写入zookeeper中
      persistOffsets(rdd ,true)
    })

    streaming.start()

    streaming.awaitTermination()
  }

  def jsonDecode(text: String): Person = {
    try {
      JsonUtils.deserialize(text, classOf[Person])
    } catch {
      case e: IOException =>
        logger.error(e.getMessage, e)
        null
    }
  }


  def persistOffsets[K, V](rdd: RDD[ConsumerRecord[K, V]], storeEndOffset: Boolean = true): Unit = {
    val groupId = ""
    val offsetsList = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
    offsetsList.foreach(or => {
      val zkGroupTopicDirs = new ZKGroupTopicDirs(groupId, or.topic)
      val offsetPath = zkGroupTopicDirs.consumerOffsetDir + "/" + or.partition
      val offsetVal = if (storeEndOffset) or.untilOffset else or.fromOffset
      zkUtils.updatePersistentPath(zkGroupTopicDirs.consumerOffsetDir + "/" + or.partition, offsetVal + "" /*, JavaConversions.bufferAsJavaList(acls)*/)
      logger.debug("保存Kafka消息偏移量详情: 话题:{}, 分区:{}, 偏移量:{}, ZK节点路径:{}", Seq[AnyRef](or.topic, or.partition.toString, offsetVal.toString, offsetPath): _*)
    })
  }


  /**
    * 获取zk中的offset
    * @param topics
    * @param groupId
    * @return
    */
  def readOffsets(topics: Seq[String], groupId: String): Map[TopicPartition, Long] = {
    val topicPartOffsetMap = collection.mutable.HashMap.empty[TopicPartition, Long]
    val partitionMap = zkUtils.getPartitionsForTopics(topics)
    // offset在zk中的路径格式为: /consumers/<groupId>/offsets/<topic>/
    partitionMap.foreach(topicPartitions => {
      val zkGroupTopicDirs = new ZKGroupTopicDirs(groupId, topicPartitions._1)
      topicPartitions._2.foreach(partition => {
        val offsetPath = zkGroupTopicDirs.consumerOffsetDir + "/" + partition
        val tryGetKafkaOffset = Try {
          val offsetStatTuple = zkUtils.readData(offsetPath)
          if (offsetStatTuple != null) {
            logger.info("查询Kafka消息偏移量详情: 话题:{}, 分区:{}, 偏移量:{}, ZK节点路径:{}", Seq[AnyRef](topicPartitions._1, partition.toString, offsetStatTuple._1, offsetPath): _*)
            topicPartOffsetMap.put(new TopicPartition(topicPartitions._1, Integer.valueOf(partition)), offsetStatTuple._1.toLong)
          }
        }
      })
    })
    topicPartOffsetMap.toMap
  }



}
