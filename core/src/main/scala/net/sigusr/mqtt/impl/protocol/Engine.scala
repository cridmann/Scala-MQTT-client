/*
 * Copyright 2014 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.protocol

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorLogging, ActorRef, Terminated }
import akka.event.LoggingReceive
import akka.util.ByteString
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.impl.frames.{PartialFrame, Frame}
import net.sigusr.mqtt.impl.protocol.Registers._
import scodec.Codec
import scodec.bits.BitVector

import scala.concurrent.duration.{ FiniteDuration, _ }
import scalaz.State

private[protocol] case object TimerSignal

abstract class Engine(mqttBrokerAddress: InetSocketAddress) extends Actor with Handlers with ActorLogging {

  import akka.io.Tcp.{ Abort ⇒ TcpAbort, CommandFailed ⇒ TcpCommandFailed, Connect ⇒ TcpConnect, Connected ⇒ TcpConnected, ConnectionClosed ⇒ TcpConnectionClosed, Received ⇒ TcpReceived, Register ⇒ TcpRegister, Write ⇒ TcpWrite }
  import context.dispatcher

  var registers: Registers = Registers(client = context.parent, tcpManager = tcpManagerActor)

  def tcpManagerActor: ActorRef

  def receive: Receive = notConnected

  private def notConnected: Receive = LoggingReceive {
    case Status ⇒
      registers = sendToClient(Disconnected).exec(registers)
    case c: Connect ⇒
      val (state, actions) = (for {
        _ ← sendToTcpManager(TcpConnect(mqttBrokerAddress))
        actions ← handleApiConnect(c)
      } yield actions).run(registers)
      registers = state
      context become connecting(actions)
    case _: APICommand ⇒
      registers = sendToClient(Error(NotConnected)).exec(registers)
  }

  private def connecting(pendingActions: Action): Receive = LoggingReceive {
    case Status ⇒
      registers = sendToClient(Disconnected).exec(registers)
    case _: APICommand ⇒
      registers = sendToClient(Error(NotConnected)).exec(registers)
    case TcpCommandFailed(_: TcpConnect) ⇒
      registers = (for {
        _ ← setTCPManager(sender())
        _ ← processAction(transportNotReady())
      } yield ()).exec(registers)
      context become notConnected
    case TcpConnected(_, _) ⇒
      registers = (for {
        _ ← setTCPManager(sender())
        _ ← sendToTcpManager(TcpRegister(self))
        _ ← processAction(pendingActions)
        _ ← watchTcpManager
      } yield ()).exec(registers)
      context become connected
  }

  def decodePartialFrame(bitVector: BitVector): Unit = {
    Codec[PartialFrame].decode(bitVector).fold[Unit](fail => {
      log.error(s"$self - Failed decoding partial frame.  Disconnecting client.")
      disconnect()
    }, par => {
      if (par.value.payload.size != par.value.remainingLength) {
        registers = (for {
          _ <- setReadBuffer(Some((par.value, bitVector)))
        } yield ()).exec(registers)
      } else {
        decodeCompleteFrame(bitVector)
      }
    })
  }

  def decodeCompleteFrame(bitVector: BitVector): Unit = {
    Codec[Frame].decode(bitVector).fold[Unit](
    { err ⇒
      log.error(s"$self - Failed decoding frame.  Disconnecting client.")
      disconnect()
    }, { d ⇒
        registers = (for {
          actions ← handleNetworkFrames(d.value)
          _ ← processAction(actions)
          _ <- setReadBuffer(None)
        } yield ()).exec(registers)
    })
  }

  private def connected: Receive = LoggingReceive {
    case message: APICommand ⇒
      registers = (for {
        actions ← handleApiCommand(message)
        _ ← processAction(actions)
      } yield ()).exec(registers)
    case TimerSignal ⇒
      registers = (for {
        actions ← timerSignal(System.currentTimeMillis())
        _ ← processAction(actions)
      } yield ()).exec(registers)
    case TcpReceived(encodedResponse) ⇒
      val bitVector = BitVector.view(encodedResponse.toArray)
      registers.readBuffer.fold[Unit](
        // attempt to decode
          // if successful, call actions
          // if partial, build buffer
        decodePartialFrame(bitVector)
      ) {
        case (parBuffer, bufferVector) =>
          // check if bitVector.size + par.payload.size == par.remainingLength
            // if successful, decode payload and call actions
            // if partial, copy buffer with bitVector added to payload
            // if greater, then call actions and store new readBuffer
          if (parBuffer.payload.size + bitVector.toByteVector.size == parBuffer.remainingLength) {
            val completeBitVector = bufferVector ++ bitVector
            decodeCompleteFrame(completeBitVector)
          } else if (parBuffer.payload.size + bitVector.toByteVector.size < parBuffer.remainingLength) {
            registers = (for {
              _ <- setReadBuffer(Some((parBuffer.copy(payload = parBuffer.payload ++ bitVector.toByteVector), bufferVector ++ bitVector)))
            } yield ()).exec(registers)
          } else if (parBuffer.payload.size + bitVector.toByteVector.size > parBuffer.remainingLength) {
            val (completedBytes, leftOver) = bitVector.splitAt((parBuffer.remainingLength - parBuffer.payload.size).toLong)
            val completeBitVector = bufferVector ++ completedBytes
            decodeCompleteFrame(completeBitVector)
            decodePartialFrame(leftOver)
          }
      }
    case Terminated(_) | _: TcpConnectionClosed ⇒
      disconnect()
  }

  private def disconnect(): Unit = {
    registers = (for {
      _ ← unwatchTcpManager
      _ ← setTCPManager(tcpManagerActor)
      _ ← resetTimerTask
      _ ← processAction(connectionClosed())
    } yield ()).exec(registers)
    context become notConnected
  }

  private def processActionSeq(actions: Seq[Action]): RegistersState[Unit] =
    if (actions.isEmpty) State { x ⇒ (x, ()) }
    else for {
      _ ← processAction(actions.head)
      _ ← processActionSeq(actions.tail)
    } yield ()

  private def processAction(action: Action): RegistersState[Unit] = action match {
    case Sequence(actions) ⇒
      processActionSeq(actions)
    case SetKeepAlive(keepAlive) ⇒
      setTimeOut(keepAlive)
    case StartPingRespTimer(timeout) ⇒
      setTimerTask(context.system.scheduler.scheduleOnce(FiniteDuration(timeout, MILLISECONDS), self, TimerSignal))
    case SetPendingPingResponse(isPending) ⇒
      setPingResponsePending(isPending)
    case SendToClient(message) ⇒
      sendToClient(message)
    case SendToNetwork(frame) ⇒
      for {
        _ ← sendToTcpManager(TcpWrite(ByteString(Codec[Frame].encode(frame).require.toByteArray)))
        _ ← setLastSentMessageTimestamp(System.currentTimeMillis())
      } yield ()
    case ForciblyCloseTransport ⇒
      sendToTcpManager(TcpAbort)
    case StoreSentInFlightFrame(id, frame) ⇒
      storeInFlightSentFrame(id, frame)
    case RemoveSentInFlightFrame(id) ⇒
      removeInFlightSentFrame(id)
    case StoreRecvInFlightFrameId(id) ⇒
      storeInFlightRecvFrame(id)
    case RemoveRecvInFlightFrameId(id) ⇒
      removeInFlightRecvFrame(id)
  }
}

