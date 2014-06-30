package com.qifun.qforce.bcp.client

import java.util.concurrent.ScheduledExecutorService
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import com.qifun.qforce.bcp.BcpSession
import java.util.concurrent.Executor
import scala.concurrent.stm.InTxn
import com.qifun.statelessFuture.Future
import com.qifun.qforce.bcp.Bcp._
import com.qifun.qforce.bcp.BcpSession
import com.qifun.qforce.bcp.BcpSession._
import scala.util.control.Exception.Catcher
import scala.PartialFunction
import com.qifun.qforce.bcp.BcpIo
import scala.reflect.classTag
import scala.concurrent.stm.atomic
import scala.concurrent.stm.Ref
import java.security.SecureRandom
import com.dongxiguo.fastring.Fastring.Implicits._
import scala.concurrent.stm.Txn

object BcpClient {

  private implicit val (logger, formater, appender) = ZeroLoggerFactory.newLogger(this)

  private[BcpClient] final class Stream(socket: AsynchronousSocketChannel) extends BcpSession.Stream(socket) {
    // TODO: 客户端专有的数据结构，比如Timer

  }

  private[BcpClient] final class Connection extends BcpSession.Connection[Stream] {
  }

}

abstract class BcpClient extends BcpSession[BcpClient.Stream, BcpClient.Connection] {

  import BcpClient.{ logger, formater, appender }

  override private[bcp] final def newConnection = new BcpClient.Connection

  protected def connect(): Future[AsynchronousSocketChannel]

  protected def executor: ScheduledExecutorService

  override private[bcp] def internalExecutor: ScheduledExecutorService = executor

  override final private[bcp] def release()(implicit txn: InTxn) {}

  private val sessionId: Array[Byte] = Array.ofDim[Byte](NumBytesSessionId)
  private val nextConnectionId = Ref(0)

  private def start() {
    val secureRandom = new SecureRandom
    secureRandom.setSeed(secureRandom.generateSeed(20))
    secureRandom.nextBytes(sessionId)
    implicit def catcher: Catcher[Unit] = PartialFunction.empty
    for (socket <- connect()) {
      logger.fine(fast"bcp client connect server success, socket: ${socket}")
      val stream = new BcpClient.Stream(socket)
      atomic { implicit txn =>
        val connectionId = nextConnectionId()
        nextConnectionId() = connectionId + 1
        Txn.afterCommit(_ => {
          BcpIo.enqueueHead(stream, ConnectionHead(sessionId, connectionId))
          stream.flush()
          logger.fine(fast"bcp client send head to server success, sessionId: ${sessionId.toSeq} , connectionId: ${connectionId}")
        })
        addStream(connectionId, stream)
      }
    }
  }

  start()

}