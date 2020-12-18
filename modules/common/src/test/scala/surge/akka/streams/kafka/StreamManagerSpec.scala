// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.akka.streams.kafka

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.testkit.{ TestKit, TestProbe }
import net.manub.embeddedkafka.{ EmbeddedKafka, EmbeddedKafkaConfig }
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike
import surge.core.DataPipeline._
import surge.core._
import surge.kafka.streams.DefaultSerdes
import surge.scala.core.kafka.KafkaTopic

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

class StreamManagerSpec extends TestKit(ActorSystem("StreamManagerSpec"))
  with AnyWordSpecLike with Matchers with EmbeddedKafka with Eventually with BeforeAndAfterAll with ScalaFutures {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(30, Seconds)), interval = scaled(Span(50, Millis)))

  private implicit val ex: ExecutionContext = ExecutionContext.global
  private implicit val stringSer: Serializer[String] = DefaultSerdes.stringSerde.serializer()
  private val stringDeser = DefaultSerdes.stringSerde.deserializer()

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  private def sendToTestProbe(testProbe: TestProbe)(key: String, value: Array[Byte]): Future[Done] = {
    val msg = stringDeser.deserialize("", value)
    testProbe.ref ! msg
    Future.successful(Done)
  }

  private def testStreamManager(topic: KafkaTopic, kafkaBrokers: String, groupId: String,
    businessLogic: (String, Array[Byte]) ⇒ Future[_], replayStrategy: EventReplayStrategy = NoOpEventReplayStrategy, replaySettings: EventReplaySettings = DefaultEventReplaySettings): KafkaStreamManager[String, Array[Byte]] = {
    val consumerSettings = KafkaConsumer.defaultConsumerSettings(system, groupId)
      .withBootstrapServers(kafkaBrokers)

    val parallelism = 16
    val tupleFlow: ((String, Array[Byte])) ⇒ Future[_] = { tup ⇒ businessLogic(tup._1, tup._2) }
    val partitionBy: ((String, Array[Byte])) ⇒ String = _._1
    val businessFlow = FlowConverter.flowFor[(String, Array[Byte]), CommittableOffset](tupleFlow, partitionBy, parallelism)
    KafkaStreamManager(topic, consumerSettings, replayStrategy, replaySettings, businessFlow)
  }

  "StreamManager" should {

    "Subscribe to events from Kafka" in {
      withRunningKafkaOnFoundPort(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)) { implicit actualConfig ⇒
        val topic = KafkaTopic("testTopic")
        createCustomTopic(topic.name, partitions = 3)
        val embeddedBroker = s"localhost:${actualConfig.kafkaPort}"
        val probe = TestProbe()

        def createManager: KafkaStreamManager[String, Array[Byte]] =
          testStreamManager(topic, kafkaBrokers = embeddedBroker, groupId = "subscription-test", sendToTestProbe(probe))

        val record1 = "record 1"
        val record2 = "record 2"
        val record3 = "record 3"
        val record4 = "record 4"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record1, record1))
        publishToKafka(new ProducerRecord[String, String](topic.name, 1, record2, record2))
        publishToKafka(new ProducerRecord[String, String](topic.name, 2, record3, record3))
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record4, record4))

        val consumer1 = createManager
        val consumer2 = createManager

        consumer1.start()
        consumer2.start()

        probe.expectMsgAllOf(10.seconds, record1, record2, record3, record4)
        consumer1.stop()
        consumer2.stop()
      }
    }

    "Continue processing elements from Kafka when the business future completes, even if it does not emit an element" in {
      withRunningKafkaOnFoundPort(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)) { implicit actualConfig ⇒
        val topic = KafkaTopic("testTopic")
        createCustomTopic(topic.name, partitions = 1)
        val embeddedBroker = s"localhost:${actualConfig.kafkaPort}"
        val probe = TestProbe()

        // Returning null here when the future completes gets us the same result as converting from a Java Future that completes with null,
        // which is typical in cases where the future is just used to signal completion and doesn't care about the return value
        def handler(key: String, value: Array[Byte]): Future[Any] = sendToTestProbe(probe)(key, value)
          .flatMap(_ ⇒ Future.successful(null)) // scalastyle:ignore null

        val record1 = "record 1"
        val record2 = "record 2"
        val record3 = "record 3"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record1, record1))
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record2, record2))
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record3, record3))

        val consumer = testStreamManager(topic, kafkaBrokers = embeddedBroker, groupId = "subscription-test", handler)
        consumer.start()
        probe.expectMsgAllOf(10.seconds, record1, record2, record3)
        consumer.stop()
      }
    }

    "Restart the stream if it fails" in {
      withRunningKafkaOnFoundPort(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)) { implicit actualConfig ⇒
        val topic = KafkaTopic("testTopic2")
        createCustomTopic(topic.name, partitions = 3)
        val embeddedBroker = s"localhost:${actualConfig.kafkaPort}"

        // TODO The group manager needs withGroupInstanceId enabled to support fast restarts without consumer group rebalance
        //  but the CMP Kafka brokers isn't a high enough version to support that yet.  Once it's updated set the expectedNumExceptions
        //  to 3 to verify we're restarting without rebalancing the consumer group as well.
        val expectedNumExceptions = 1
        var exceptionCount = 0

        var receivedRecords: Seq[String] = Seq.empty
        def businessLogic(key: String, value: Array[Byte]): Future[Done] = {
          if (exceptionCount < expectedNumExceptions) {
            exceptionCount = exceptionCount + 1
            throw new RuntimeException("This is expected")
          }
          val record = stringDeser.deserialize(topic.name, value)
          receivedRecords = receivedRecords :+ record
          Future.successful(Done)
        }

        def createManager: KafkaStreamManager[String, Array[Byte]] =
          testStreamManager(topic, kafkaBrokers = embeddedBroker, groupId = "restart-test", businessLogic)

        val record1 = "record 1"
        val record2 = "record 2"
        val record3 = "record 3"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record1, record1))
        publishToKafka(new ProducerRecord[String, String](topic.name, 1, record2, record2))
        publishToKafka(new ProducerRecord[String, String](topic.name, 2, record3, record3))

        val consumer1 = createManager

        consumer1.start()

        eventually {
          receivedRecords should contain(record1)
          receivedRecords should contain(record2)
          receivedRecords should contain(record3)
        }
        consumer1.stop()
      }
    }

    "Be able to stop the stream" in {
      withRunningKafkaOnFoundPort(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)) { implicit actualConfig ⇒
        val topic = KafkaTopic("testTopic3")
        createCustomTopic(topic.name, partitions = 3)
        val embeddedBroker = s"localhost:${actualConfig.kafkaPort}"
        val probe = TestProbe()

        def createManager: KafkaStreamManager[String, Array[Byte]] =
          testStreamManager(topic, kafkaBrokers = embeddedBroker, groupId = "stop-test", sendToTestProbe(probe))

        val record1 = "record 1"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record1, record1))

        val consumer = createManager

        consumer.start()
        probe.expectMsg(20.seconds, record1)

        consumer.stop()
        val record2 = "record 2"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record2, record2))
        probe.expectNoMessage()

        consumer.start()
        probe.expectMsg(20.seconds, record2)
      }
    }

    "Be able to replay a stream" in {
      withRunningKafkaOnFoundPort(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)) { implicit actualConfig ⇒
        val topic = KafkaTopic("testTopic4")
        createCustomTopic(topic.name, partitions = 3)
        val embeddedBroker = s"localhost:${actualConfig.kafkaPort}"
        val probe = TestProbe()

        val record1 = "record 1"
        val record2 = "record 2"
        val record3 = "record 3"
        publishToKafka(new ProducerRecord[String, String](topic.name, 0, record1, record1))
        publishToKafka(new ProducerRecord[String, String](topic.name, 1, record2, record2))
        publishToKafka(new ProducerRecord[String, String](topic.name, 2, record3, record3))

        val settings = KafkaForeverReplaySettings(topic.name).copy(brokers = List(embeddedBroker))
        val kafkaForeverReplayStrategy = KafkaForeverReplayStrategy.create(system, settings)
        val consumer = testStreamManager(topic, kafkaBrokers = embeddedBroker, groupId = "replay-test", sendToTestProbe(probe), kafkaForeverReplayStrategy, settings)

        consumer.start()
        probe.expectMsgAllOf(20.seconds, record1, record2, record3)
        val replayResult = consumer.replay().futureValue(Timeout(settings.entireReplayTimeout))
        replayResult shouldBe ReplaySuccessfullyStarted()
        probe.expectMsgAllOf(40.seconds, record1, record2, record3)
      }
    }
  }
}