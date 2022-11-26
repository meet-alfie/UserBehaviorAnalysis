package com.example.loginfali_detect

import org.apache.flink.cep.PatternSelectFunction
import org.apache.flink.cep.scala.CEP
import org.apache.flink.cep.scala.pattern.Pattern
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.time.Time

import java.util

object LoginFailWithCep {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    // 1.读取事件数据,创建简单事件流
    val resource = getClass.getResource("/LoginLog.csv")
    val loginEventStream = env.readTextFile(resource.getPath)
      .map(data =>{
        val dataArray = data.split(",")
        LoginEvent(dataArray(0).trim.toLong, dataArray(1).trim, dataArray(2).trim, dataArray(3).trim.toLong)
      })
      .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[LoginEvent](Time.seconds(5)) {
        override def extractTimestamp(element: LoginEvent): Long = element.eventTime * 1000L
      })
      .keyBy(_.userId)

    // 2. 定义匹配模式
    /**
     * 即使乱序，有迟到数据，先到数据成功了，也通过水位线保证迟到数据能正确处理
     * 输入：
     * 6745,66.249.73.185,fail,1558430859
     * 6745,66.249.73.185,success,1558430861
     * 6745,66.249.73.185,fail,1558430860
     *
     * 输出：
     * Warning(6745,1558430859,1558430860,login fail!)
     */
    val loginFailPattern = Pattern.begin[LoginEvent]("begin").where(_.eventType == "fail")
      .next("next").where(_.eventType == "fail")
      .within(Time.seconds(2))

    // 3. 在事件流上应用模式，得到一个pattern stream
    val patternStream = CEP.pattern(loginEventStream, loginFailPattern)

    // 4. 从patternStream上应用select function,检出匹配事件序列
    val loginFailDataStream = patternStream.select( new LoginFailMatch())

    loginFailDataStream.print()

    env.execute("login fail with cep job")
  }

}

class LoginFailMatch() extends PatternSelectFunction[LoginEvent, Warning]{
  // 检测到的所有的事件序列 ： begin ,next 事件
  override def select(map: util.Map[String, util.List[LoginEvent]]): Warning = {
    // 从map中按照名称取出对应的事件
    val firstFail = map.get("begin").iterator().next()
    val lastFail = map.get("next").iterator().next()
    Warning(firstFail.userId, firstFail.eventTime, lastFail.eventTime, "login fail!")
  }
}
