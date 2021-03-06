import java.nio.file.Paths

import org.apache.flink.streaming.api.scala._
import java.util.concurrent.TimeUnit

import eval.Evaluator
import event._
import input.{InputConverter, StreamHeader}
import pipes.rul.{DefaultRuleProcessor, HybridRulesAggregator, PartialMetricsProcessor}
import utils.{Files, ModuloPartitioner, SimpleMerge}

object HybridRulesJob {

  val metricsUpdateTag = new OutputTag[MetricsEvent]("metrics-update")
  val forwardedInstancesTag = new OutputTag[Event]("forwarded-instances")

  def main(args: Array[String]) {
    println("Running Hybrid Rules: " + args.mkString(" "))

        if (args.length < 4) {
          println("Too few parameters, expected: 4. Usage: java -jar vertical.jar data/ELEC.arff 8 100 5000")
          System.exit(1)
        }

        val arffPath = s"${Paths.get(".").toAbsolutePath}/${args(0)}" //"data\\ELEC.arff"
        val numPartitions = args(1).toInt //8
        val extMin = args(2).toInt //100
        val itMaxDelay = args(3).toInt //5000

    println(s"Starting HybridRulesJob with: $arffPath $numPartitions $extMin $itMaxDelay")

    val streamHeader: StreamHeader = new StreamHeader(arffPath).parse()
    streamHeader.print()

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)

    val rawInputStream = env.readTextFile(arffPath).filter(line => !line.startsWith("@") && !line.isEmpty)
    val eventsStream = rawInputStream.map(new InputConverter(streamHeader))

    val mainStream: DataStream[Event] = eventsStream.iterate((iteration: DataStream[Event]) =>
    {
      val instancesStream = iteration.filter(e => e.isInstanceOf[InstanceEvent])
      val ruleUpdatesBroadcastStream = iteration.filter(e => e.isInstanceOf[RuleEvent]).broadcast()

      val predictionsStream = instancesStream
        .connect(ruleUpdatesBroadcastStream)
        .process(new HybridRulesAggregator(streamHeader, extMin, metricsUpdateTag, forwardedInstancesTag))
        .setParallelism(numPartitions)

      val metricsUpdateRequestsStream = predictionsStream.getSideOutput(metricsUpdateTag)
      val forwardedInstancesStream = predictionsStream.getSideOutput(forwardedInstancesTag)

      val newRulesStream = forwardedInstancesStream.process(new DefaultRuleProcessor(streamHeader, extMin, metricsUpdateTag))
      val newMetricsStream = newRulesStream.getSideOutput(metricsUpdateTag)

      val metricsUpdatesStream: DataStream[MetricsEvent] = metricsUpdateRequestsStream.connect(newMetricsStream).process(new SimpleMerge)

      val newConditionsStream = metricsUpdatesStream
        .map((e: MetricsEvent) => (e, e.ruleId))
        .partitionCustom(new ModuloPartitioner(numPartitions), 1)
        .flatMap(new PartialMetricsProcessor(streamHeader, extMin))
        .setParallelism(numPartitions)
        .map(e => e)

      val ruleUpdatesStream = newConditionsStream.connect(newRulesStream).process(new SimpleMerge)

      (ruleUpdatesStream, predictionsStream)
    }, itMaxDelay)

    val resultsStream = mainStream.map(new Evaluator())

    //resultsStream.print()
    //resultsStream.countWindowAll(1000, 1000).sum(0).map(s => s / 1000.0).print()

    val result = env.execute("HybridRulesJob")

    val correct: Double = result.getAccumulatorResult("correct-counter")
    val all: Double = result.getAccumulatorResult("all-counter")
    val accuracy: Double = correct / all
    val time: Long = result.getNetRuntime(TimeUnit.MILLISECONDS) - itMaxDelay

    println(s"Accuracy: $accuracy")
    System.out.println(s"Execution time: $time ms")

    Files.writeResultsToFile("results.csv", arffPath, accuracy, time, "Vertical", numPartitions)
  }

}
