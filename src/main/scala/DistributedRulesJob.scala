import org.apache.flink.streaming.api.scala._
import java.util.concurrent.TimeUnit

import eval.Evaluator
import input.{InputConverter, Instance, StreamHeader}
import utils.ReplicateInstance
import pipes.rul.{Event, PartialRulesProcessor, RulesAggregator}
import pipes.base.Predictor
import utils.{IntegerPartitioner, ReplicateInstance}

object DistributedRulesJob {

  val outputTag = new OutputTag[Event]("side-output")

  def main(args: Array[String]) {
    println("Starting")
    val numPartitions = 4
    val arffPath = "data\\ELEC.arff"
    val streamHeader: StreamHeader = new StreamHeader(arffPath).parse()
    streamHeader.print()

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)

    val input: DataStream[Event] = env.fromCollection(Array.fill(1000)(Event("Instance")))

    val mainStream: DataStream[Event] = input.iterate((iteration: DataStream[Event]) =>
    {
      val predictionsStream = iteration.process(new RulesAggregator(outputTag))//.setParallelism(1) // rule updates will go to someone else?
      val rulesUpdatesStream = predictionsStream.getSideOutput(outputTag)

      val newConditionsStream = rulesUpdatesStream
        .flatMap(new ReplicateInstance(numPartitions))
        .partitionCustom(new IntegerPartitioner(numPartitions), 0)
        .flatMap(new PartialRulesProcessor())
        .setParallelism(numPartitions)
        .map(e => e)
        .setParallelism(1)

      newConditionsStream.print()

      (newConditionsStream, predictionsStream) // interleave it more?
    }, 1)

    mainStream.print()

//    val rawInputStream = env.readTextFile(arffPath).filter(line => !line.startsWith("@") && !line.isEmpty)
//    val instancesStream = rawInputStream.map(new InputConverter(streamHeader))
//    val predictionsStream = instancesStream.map(new Predictor(streamHeader))
//    val resultsStream = predictionsStream.map(new Evaluator())

    //resultsStream.countWindowAll(1000, 1000).sum(0).map(s => s / 1000.0).print()

    val result = env.execute("Distributed AMRules")

//    val correct: Double = result.getAccumulatorResult("correct-counter")
//    val all: Double = result.getAccumulatorResult("all-counter")
//
    System.out.println("Execution time: " + result.getNetRuntime(TimeUnit.MILLISECONDS) + " ms")
//    println("Accuracy: " + (correct / all))
  }

}
