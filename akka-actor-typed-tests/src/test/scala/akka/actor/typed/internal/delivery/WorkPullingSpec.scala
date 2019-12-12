/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.delivery

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

object WorkPullingSpec {
  val workerServiceKey: ServiceKey[ConsumerController.Command[TestConsumer.Job]] = ServiceKey("worker")

  object TestProducerWorkPulling {

    trait Command
    final case class RequestNext(sendTo: ActorRef[TestConsumer.Job]) extends Command
    private final case object Tick extends Command

    def apply(
        delay: FiniteDuration,
        producerController: ActorRef[WorkPullingProducerController.Start[TestConsumer.Job]]): Behavior[Command] = {
      Behaviors.setup { context =>
        context.setLoggerName("TestProducerWorkPulling")
        val requestNextAdapter: ActorRef[WorkPullingProducerController.RequestNext[TestConsumer.Job]] =
          context.messageAdapter(req => RequestNext(req.sendNextTo))
        producerController ! WorkPullingProducerController.Start(requestNextAdapter)

        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, Tick, delay)
          idle(0)
        }
      }
    }

    private def idle(n: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case Tick                => Behaviors.same
        case RequestNext(sendTo) => active(n + 1, sendTo)
      }
    }

    private def active(n: Int, sendTo: ActorRef[TestConsumer.Job]): Behavior[Command] = {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Tick =>
            val msg = s"msg-$n"
            ctx.log.info("sent {}", msg)
            sendTo ! TestConsumer.Job(msg)
            idle(n)

          case RequestNext(_) =>
            throw new IllegalStateException("Unexpected RequestNext, already got one.")
        }
      }
    }

  }
}

class WorkPullingSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import WorkPullingSpec._
  import TestConsumer.defaultConsumerDelay
  import TestProducer.defaultProducerDelay

  private var idCount = 0
  private def nextId(): Int = {
    idCount += 1
    idCount
  }

  private def producerId: String = s"p-$idCount"

  private def awaitWorkersRegistered(
      controller: ActorRef[WorkPullingProducerController.Command[TestConsumer.Job]],
      count: Int): Unit = {
    val probe = createTestProbe[WorkPullingProducerController.WorkerStats]()
    probe.awaitAssert {
      controller ! WorkPullingProducerController.GetWorkerStats(probe.ref)
      probe.receiveMessage().numberOfWorkers should ===(count)
    }

  }

  "ReliableDelivery with work-pulling" must {

    "illustrate work-pulling usage" in {
      nextId()
      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey),
          s"workPullingController-${idCount}")
      val jobProducer =
        spawn(TestProducerWorkPulling(defaultProducerDelay, workPullingController), name = s"jobProducer-${idCount}")

      val consumerEndProbe1 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController1 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController1-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe1.ref, workerController1),
        name = s"worker1-${idCount}")
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1)

      val consumerEndProbe2 = createTestProbe[TestConsumer.CollectedProducerIds]()
      val workerController2 =
        spawn(ConsumerController[TestConsumer.Job](resendLost = true), s"workerController2-${idCount}")
      spawn(
        TestConsumer(defaultConsumerDelay, 42, consumerEndProbe2.ref, workerController2),
        name = s"worker2-${idCount}")
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController2)

      consumerEndProbe1.receiveMessage(10.seconds)
      consumerEndProbe2.receiveMessage()

      testKit.stop(workerController1)
      testKit.stop(workerController2)
      awaitWorkersRegistered(workPullingController, 0)
      testKit.stop(jobProducer)
      testKit.stop(workPullingController)
    }

    "resend unconfirmed to other if worker dies" in {
      nextId()
      val workPullingController =
        spawn(
          WorkPullingProducerController[TestConsumer.Job](producerId, workerServiceKey),
          s"workPullingController-${idCount}")
      val producerProbe = createTestProbe[WorkPullingProducerController.RequestNext[TestConsumer.Job]]()
      workPullingController ! WorkPullingProducerController.Start(producerProbe.ref)

      val workerController1Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController1Probe.ref)
      awaitWorkersRegistered(workPullingController, 1)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-1")
      val seqMsg1 = workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg1.msg should ===(TestConsumer.Job("msg-1"))
      seqMsg1.producer ! ProducerController.Internal.Request(1L, 10L, true, false)

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-2")
      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]].msg should ===(
        TestConsumer.Job("msg-2"))
      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-3")
      workerController1Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]].msg should ===(
        TestConsumer.Job("msg-3"))

      val workerController2Probe = createTestProbe[ConsumerController.Command[TestConsumer.Job]]()
      system.receptionist ! Receptionist.Register(workerServiceKey, workerController2Probe.ref)
      awaitWorkersRegistered(workPullingController, 2)

      workerController1Probe.stop()
      awaitWorkersRegistered(workPullingController, 1)

      // msg-2 and msg3 were not confirmed and should be resent to another worker
      val seqMsg2 = workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]]
      seqMsg2.msg should ===(TestConsumer.Job("msg-2"))
      seqMsg2.seqNr should ===(1)
      seqMsg2.producer ! ProducerController.Internal.Request(1L, 10L, true, false)

      workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]].msg should ===(
        TestConsumer.Job("msg-3"))

      producerProbe.receiveMessage().sendNextTo ! TestConsumer.Job("msg-4")
      workerController2Probe.expectMessageType[ConsumerController.SequencedMessage[TestConsumer.Job]].msg should ===(
        TestConsumer.Job("msg-4"))

      testKit.stop(workPullingController)
    }

  }

}

// FIXME add a random test for work pulling
