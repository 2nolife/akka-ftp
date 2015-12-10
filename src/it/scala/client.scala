package com.coldcore.akkaftp.it
package client

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{Tcp, IO}
import akka.util.{CompactByteString, ByteString}
import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.ftp.core.Constants._
import akka.pattern.ask
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._

class FtpClient(ftpstate: FtpState)  {
  import ExecutionContext.Implicits.global

  private var system: ActorSystem = _
  private[client] var ctrl: ActorRef = _
  var replies: List[Reply] = _

  def connect() {
    system = ActorSystem("it-client-system-"+System.currentTimeMillis)
    system.actorOf(CtrlConnector.props(new InetSocketAddress(ftpstate.hostname, ftpstate.port), this))
    replies = List.empty[Reply]
  }

  def disconnect() = system.shutdown()

  def -->(text: String) = ctrl ! CtrlConnection.Send(text)

  def <->(text: String): Reply = {
    val x = ctrl.ask(CtrlConnection.Send(text))(1 second).map {
      case reply @ Reply(_, _) => reply
    } recover {
      case _ => throw new IllegalStateException("Failed to receive server reply")
    }
    Await.result(x, 1 second)
  }
}

object CtrlConnector {
  def props(endpoint: InetSocketAddress, client: FtpClient): Props =
    Props(new CtrlConnector(endpoint, client))
}

class CtrlConnector(endpoint: InetSocketAddress, client: FtpClient) extends Actor with ActorLogging {
  IO(Tcp)(context.system) ! Tcp.Connect(endpoint)

  def receive = {
    case Tcp.Connected(remote, _) =>
      log.debug("Connected to remote address {}", remote)
      sender ! Tcp.Register(context.actorOf(CtrlConnection.props(remote, sender, client)))

    case Tcp.CommandFailed(_: Tcp.Connect) => // cannot connect
      log.debug("Connection to remote endpoint {} failed", endpoint.getHostName+":"+endpoint.getPort)
      context.stop(self)
  }

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _ =>
        context.stop(self)
        SupervisorStrategy.Stop
    }
}

object CtrlConnection {
  def props(remote: InetSocketAddress, connection: ActorRef, client: FtpClient): Props =
    Props(new CtrlConnection(remote, connection, client))

  case class Send(text: String)
}

class CtrlConnection(remote: InetSocketAddress, connection: ActorRef, client: FtpClient) extends Actor with ActorLogging {
  import CtrlConnection._
  implicit def String2ByteString(x: String): ByteString = CompactByteString(x)

  case class Extracted(reply: Reply)

  class Buffer {
    val buffer = new StringBuilder
    def append(x: String) = buffer.append(x)

    def extract: Option[String] = {
      val str = buffer.toString
      if (str.contains(EoL)) {
        buffer.delete(0, str.indexOf(EoL)+EoL.length)
        str.split(EoL).headOption
      } else None
    }
  }

  val buffer = new Buffer
  var receiver: Option[ActorRef] = None

  context.watch(connection)
  client.ctrl = self

  def receive = {
    case Tcp.Received(data) => // server sends data
      buffer.append(data.utf8String)
      buffer.extract match {
        case Some(text) => // process the reply
          log.debug("{} ---> {}", remote, text)
          val reply = Reply(text) // receive as is
          self ! Extracted(reply)
        case None =>
      }

    case Extracted(reply) =>
      client.replies = reply +: client.replies
      receiver.foreach(_ ! reply)
      receiver = None
      self ! Tcp.Received("") // loop to process the next reply from the buffer

    case Send(text) =>
      connection ! Tcp.Write(text+EoL) // send as is
      log.debug("{} <--- {}", remote, text)
      receiver = Some(sender)

    case _: Tcp.ConnectionClosed =>
      log.debug("Connection for remote address {} closed", remote)
      context.stop(self)

    case Terminated(`connection`) =>
      log.debug("Connection for remote address {} died", remote)
      context.stop(self)

    case Tcp.CommandFailed(_) =>
      log.debug("Connection for remote address {} failed", remote)
      context.stop(self)
  }
}

object Reply {
  def apply(content: String) = {
    val code = content.split(" ").head.toInt
    val text = content.drop(code.toString.size+1)
    new Reply(code, text)
  }
}

case class Reply(code: Int, text: String)