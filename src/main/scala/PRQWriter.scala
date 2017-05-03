package bclconverter

import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import cz.adamh.utils.NativeUtils
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.Properties
import org.apache.flink.api.common.io.OutputFormat
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala.hadoop.mapreduce.HadoopOutputFormat
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.{Window, GlobalWindow}
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer010, FlinkKafkaProducer010}
import org.apache.flink.streaming.util.serialization.{KeyedSerializationSchema, KeyedDeserializationSchema}
import org.apache.hadoop.conf.{Configuration => HConf}
import org.apache.hadoop.fs.{FileSystem, FSDataInputStream, FSDataOutputStream, Path => HPath}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.compress.zlib.{ZlibCompressor, ZlibFactory}
import org.apache.hadoop.io.{NullWritable, LongWritable}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat => MapreduceFileOutputFormat}
import org.apache.hadoop.mapreduce.{Job, RecordWriter, TaskAttemptContext}
import org.seqdoop.hadoop_bam.{AnySAMInputFormat, CRAMInputFormat, SAMRecordWritable, KeyIgnoringCRAMOutputFormat, KeyIgnoringCRAMRecordWriter, KeyIgnoringBAMOutputFormat, KeyIgnoringBAMRecordWriter}
import scala.collection.parallel._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.collection.JavaConversions._

import org.apache.kafka.clients.consumer.KafkaConsumer
import bclconverter.reader.Reader.{Block, PRQData}


class MyPRQDeserializer extends KeyedDeserializationSchema[(String, PRQData)] {
  // Main methods
  override def getProducedType = TypeInformation.of(classOf[(String, PRQData)])
  override def isEndOfStream(el : (String, PRQData)) : Boolean = false
  override def deserialize(key : Array[Byte], data : Array[Byte], topic : String, partition : Int, offset : Long) : (String, PRQData) = {
    val (s1, r1) = data.splitAt(4)
    val (p1, d1) = r1.splitAt(toInt(s1))
    val (s2, r2) = d1.splitAt(4)
    val (p2, d2) = r2.splitAt(toInt(s2))
    val (s3, r3) = d2.splitAt(4)
    val (p3, d3) = r3.splitAt(toInt(s3))
    val (s4, r4) = d3.splitAt(4)
    val (p4, d4) = r4.splitAt(toInt(s4))
    val (s5, p5) = d4.splitAt(4)
    (new String(key), (p1, p2, p3, p4, p5))
  }
  def toInt(a : Array[Byte]) : Int = {
    ByteBuffer.wrap(a).getInt
  }
}

class ConsProps(pref: String) extends Properties {
  private val pkeys = Seq("bootstrap.servers", "group.id", "request.timeout.ms",
  "value.deserializer", "key.deserializer").map(pref + _)
  lazy val typesafeConfig = ConfigFactory.load()

  pkeys.map{ key =>
    if (typesafeConfig.hasPath(key)) {
      put(key.replace(pref, ""), typesafeConfig.getString(key))
    }
  }

  def getCustomString(key: String) = typesafeConfig.getString(key)
  def getCustomInt(key: String) = typesafeConfig.getInt(key)
}


class WData extends Serializable{
  // parameters
  var root : String = null
  var fout : String = null
  var flinkpar = 1
  var kafkaTopic : String = "prq"
  var kafkaControl : String = "kcon"
  def setParams(param : ParameterTool) = {
    root = param.getRequired("root")
    fout = param.getRequired("fout")
    flinkpar = param.getInt("writerflinkpar", flinkpar)
    kafkaTopic = param.get("kafkaTopic", kafkaTopic)
    kafkaControl = param.get("kafkaControl", kafkaControl)
    roba.rapipar = param.getInt("rapipar", roba.rapipar)
  }
}

object Writer {
  def MyFS(path : HPath = null) : FileSystem = {
    var fs : FileSystem = null
    val conf = new HConf
    if (path == null)
      fs = FileSystem.get(conf)
    else {
      fs = FileSystem.get(path.toUri, conf);
    }
    // return the filesystem
    fs
  }
  val cp = new ConsProps("conconsumer10.")
  cp.put("enable.auto.commit", "true")
  cp.put("auto.commit.interval.ms", "1000")
  val conConsumer = new KafkaConsumer[Int, String](cp)
}

class Writer extends Serializable{
  val wd = new WData
  var sampleMap = Map[(Int, String), String]()
  // process tile, CRAM output, PRQ as intermediate format
  def kafka2cram(jid : Int, filenames : Array[String]) = {
    val FP = StreamExecutionEnvironment.getExecutionEnvironment
    FP.setParallelism(wd.flinkpar)
    def finalizeOutput(p : String) = {
      val opath = new HPath(p)
      val job = Job.getInstance(new HConf)
      MapreduceFileOutputFormat.setOutputPath(job, opath)
      val hof = new HadoopOutputFormat(new SAM2CRAM, job)
      hof.finalizeGlobal(1)
    }
    def writeToOF(x : (DataStream[SAMRecordWritable], String)) = {
      val opath = new HPath(x._2)
      val job = Job.getInstance(new HConf)
      MapreduceFileOutputFormat.setOutputPath(job, opath)
      val hof = new HadoopOutputFormat(new SAM2CRAM, job)
      x._1.map(s => (new LongWritable(123), s)).writeUsingOutputFormat(hof).setParallelism(1)
    }
    def readFromKafka : DataStream[(String, PRQData)] = {
      val cons = new FlinkKafkaConsumer010[(String, PRQData)](wd.kafkaTopic + jid.toString, new MyPRQDeserializer, new ConsProps("outconsumer10."))
      val ds = FP
        .addSource(cons)
      ds
    }
    // start here
    val stuff = readFromKafka
    // val prq2sam = new PRQ2SAMRecord(roba.sref)
    val sam = stuff
      .keyBy(0)
      .countWindow(32*1024)
      .apply(new PRQ2SAMRecord(roba.sref))
    // TODO: work here
    val splitted = sam.split(x => List(x._1))
    val jobs = filenames.map(f => (splitted.select(f).map(_._2), f))
    jobs.foreach(writeToOF)
    FP.execute
    filenames.foreach(finalizeOutput)
  }
}

object runWriter {
  def main(args: Array[String]) {
    val propertiesFile = "conf/bclconverter.properties"
    val param = ParameterTool.fromPropertiesFile(propertiesFile)
    val numTasks = param.getInt("numWriters") // concurrent flink tasks to be run
    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(numTasks))
    // implicit val timeout = Timeout(30 seconds)

    val rw = new Writer
    rw.wd.setParams(param)

    Writer.conConsumer.subscribe(List(rw.wd.kafkaControl))
    while (true) {
      val records = Writer.conConsumer.poll(1000)
      records.foreach(r => println(r.key))
      val jobs = records.map(r => Future{rw.kafka2cram(r.key, r.value.split("\n"))})
      val aggregated = Future.sequence(jobs)
      Await.result(aggregated, Duration.Inf)
    }
  }
}
