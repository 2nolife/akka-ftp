package com.coldcore.akkaftp.it
package client

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{Channels, ReadableByteChannel}

import akka.actor._
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.{ByteString, CompactByteString, Timeout}
import com.coldcore.akkaftp.ftp.core.Constants._
import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.it.Utils._
import org.scalatest.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class FtpClient(val ftpstate: FtpState) extends Matchers {
  import scala.concurrent.ExecutionContext.Implicits.global

  private var system: ActorSystem = _
  private[client] var ctrl: ActorRef = _
  private var portOrPasv: Option[PortOrPasvMode] = None
  implicit val timeout: Timeout = 3.seconds

  var replies: List[Reply] = _
  var portPort = 6004
  var connected = false

  def connect() {
    system = ActorSystem("it-client-"+System.currentTimeMillis)
    system.actorOf(CtrlConnector.props(this), name = "ctrl")
    replies = List.empty[Reply]
    delay(1 second)
  }

  def disconnect() = system.terminate()

  /** send a command to the server (fire and forget) */
  def -->(text: String) = ctrl ! CtrlConnection.Send(text)

  /** send a command to the server and return a reply */
  def <--(text: String): Reply = {
    val x = cconSuccess(ctrl ? CtrlConnection.Send(text))
    Await.result(x, timeout.duration)
  }

  def nextReply: Reply = {
    val x = cconSuccess(ctrl ? CtrlConnection.Send(""))
    Await.result(x, timeout.duration)
  }

  def anonymousLogin() = login("anonymous", "anon@anon")

  def login(username: String, password: String) {
    ((this <-- s"USER $username") code) should be (331)
    ((this <-- s"PASS $password") code) should be (230)
  }

  def cwd(dir: String) {
    ((this <-- s"CWD $dir") code) should be (250)
  }

  def portMode(port: Int = portPort) {
    portOrPasv = None
    val i = port/256
    val pstr = i+","+(port-i*256)
    val ipcs = ftpstate.hostname.replaceAll("\\.", ",")
    ((this <-- s"PORT $ipcs,$pstr") code) should be (200)
    portOrPasv = Some(PortMode(port))
  }

  def pasvMode() {
    portOrPasv = None
    val reply = this <-- "PASV"
    reply.code shouldBe 227
    val port = reply.text.dropWhile('('!=).drop(1).takeWhile(')'!=).split(",") match {
      case Array(_, _, _, _, p1, p2) => p1.toInt*256+p2.toInt
    }
    portOrPasv = Some(PasvMode(port))
  }

  private val dconSuccess = (x: Future[Any]) =>
    x.map {
      case DataConnector.Success(n, bytes) => (n, bytes)
    } recover {
      case _ => throw new IllegalStateException("Failed to transfer data to/from server")
    }

  private val cconSuccess = (x: Future[Any]) =>
    x.map {
      case reply @ Reply(_, _) => reply
    } recover {
      case _ => throw new IllegalStateException("Failed to receive server reply")
    }

  /** send a data to the server (return the data send) */
  private def sendData(command: String, data: Array[Byte]): (Long, Array[Byte]) = {
    if (portOrPasv.isEmpty) pasvMode()
    val in = new ByteArrayInputStream(data)
    val ref = system.actorOf(DataConnector.props(this), name = "data")
    val x = dconSuccess(ref ? DataConnector.Send(Channels.newChannel(in), portOrPasv.get))
    (this <-- command).code shouldBe 150
    nextReply.code shouldBe 226
    portOrPasv = None
    Await.result(x, timeout.duration)
  }

  /** retrieve a data from the server */
  private def readData(command: String): (Long, Array[Byte]) = {
    if (portOrPasv.isEmpty) pasvMode()
    val ref = system.actorOf(DataConnector.props(this), name = "data")
    val x = dconSuccess(ref ? DataConnector.Receive(portOrPasv.get))
    (this <-- command).code shouldBe 150
    nextReply.code shouldBe 226
    portOrPasv = None
    Await.result(x, timeout.duration)
  }

  def <==(filename: String, data: Array[Byte]): (Long, Array[Byte]) =
    sendData(s"STOR $filename", data)

  def appe(filename: String, data: Array[Byte]): (Long, Array[Byte]) =
    sendData(s"APPE $filename", data)

  def <==(filename: String): (Long, Array[Byte]) =
    readData(s"RETR $filename")

  def list(command: String): (Long, String) =
    readData(command) match { case (n, bytes) => (n, new String(bytes, UTF8)) }
}

object CtrlConnector {
  def props(client: FtpClient): Props =
    Props(new CtrlConnector(client))
}

class CtrlConnector(client: FtpClient) extends Actor with ActorLogging {
  val endpoint = new InetSocketAddress(client.ftpstate.hostname, client.ftpstate.port)
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
  import com.coldcore.akkaftp.it.client.CtrlConnection._
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
  client.connected = true

  def extracted(reply: Reply) {
    client.replies = reply :: client.replies
    receiver.foreach(_ ! reply)
    receiver = None
    self ! Tcp.Received("") // loop to process the next reply from the buffer
  }

  def receive = {
    case Tcp.Received(data) => // server sends data
      buffer.append(data.utf8String)
      buffer.extract match {
        case Some(text) => // process the reply
          log.debug("{} ---> {}", remote, text)
          val reply = Reply(text) // receive as is
          extracted(reply)
        case None =>
      }

    case Send("") => // catch server reply
      receiver = Some(sender)

    case Send(text) => // send a command to the server
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

  override def postStop() = {
    client.connected = false
    super.postStop()
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

sealed trait SendOrReceiveData
case object SendData extends SendOrReceiveData
case object ReceiveData extends SendOrReceiveData

sealed trait PortOrPasvMode
case class PortMode(port: Int) extends PortOrPasvMode
case class PasvMode(port: Int) extends PortOrPasvMode

object DataConnector {
  def props(client: FtpClient): Props =
    Props(new DataConnector(client))

  case class Send(rbc: ReadableByteChannel, portOrPasv: PortOrPasvMode)
  case class Receive(portOrPasv: PortOrPasvMode)
  case class Success(n: Long, bytes: Array[Byte])
}

class DataConnector(client: FtpClient) extends Actor with ActorLogging {
  import DataConnector._
  import context.dispatcher

  var endpoint: InetSocketAddress = _
  var receiver: ActorRef = _
  var sendOrReceive: SendOrReceiveData = _
  var pop: PortOrPasvMode = _
  var channel: Option[ReadableByteChannel] = None
  var socketActor: Option[ActorRef] = None
  var dataconn: ActorRef = _

  case object StartSendingData

  def open() =
    pop match {
      case PasvMode(port) =>
        endpoint = new InetSocketAddress(client.ftpstate.hostname, port)
        IO(Tcp)(context.system) ! Tcp.Connect(endpoint)
      case PortMode(port) =>
        endpoint = new InetSocketAddress(client.ftpstate.hostname, port)
        IO(Tcp)(context.system) ! Tcp.Bind(self, endpoint)
    }

  def receive = {
    case Send(rbc, portOrPasv) =>
      receiver = sender
      sendOrReceive = SendData
      channel = Some(rbc)
      pop = portOrPasv
      open()

    case Receive(portOrPasv) =>
      receiver = sender
      sendOrReceive = ReceiveData
      pop = portOrPasv
      open()

    case Tcp.Connected(remote, _) =>
      log.debug("Connected to remote address {}", remote)
      dataconn = context.actorOf(DataConnection.props(remote, sender, sendOrReceive))
      sender ! Tcp.Register(dataconn)
      //todo How to know when the connection is registered thus the data can be pushed to the server?
      context.system.scheduler.scheduleOnce(500.milliseconds, self, StartSendingData)

    case StartSendingData => //send data to the server
      channel.foreach(dataconn !)

    case Tcp.Bound(remote) =>
      socketActor = Some(sender)
      log.info(s"Data listener bound to {}", remote)

    case Tcp.Unbound =>
      log.info(s"Data listener unbound")
      context.stop(self)

    case Tcp.CommandFailed(_: Tcp.Connect) => // cannot connect
      log.debug("Connection to remote endpoint {} failed", endpoint.getHostName+":"+endpoint.getPort)
      context.stop(self)

    case x @ Success(_, _) => // data transferred
      receiver ! x
      pop match {
        case PasvMode(_) => context.stop(self)
        case PortMode(_) => socketActor.foreach(_ ! Tcp.Unbind)
      }
  }

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _ =>
        context.stop(self)
        SupervisorStrategy.Stop
    }
}

object DataConnection {
  def props(remote: InetSocketAddress, connection: ActorRef, sendOrReceive: SendOrReceiveData): Props =
    Props(new DataConnection(remote, connection, sendOrReceive))
}

class DataConnection(remote: InetSocketAddress, connection: ActorRef, sendOrReceive: SendOrReceiveData) extends Actor with ActorLogging {
  implicit def Buffer2ByteString(x: ByteBuffer): ByteString = CompactByteString(x)

  context.watch(connection)

  var transferredBytes = 0L
  val memstream = new ByteArrayOutputStream
  val memchannel = Channels.newChannel(memstream)
  val buffer = ByteBuffer.allocate(1024*8) // 8 KB buffer

  def receive = {
    case Tcp.Received(data) => // the server sends data
      val b = data.asByteBuffer
      val i = memchannel.write(b)
      transferredBytes += i

    case rbc: ReadableByteChannel => // send data to the server
      buffer.clear()
      val i = rbc.read(buffer)
      buffer.flip()
      if (i != -1) {
        connection ! Tcp.Write(buffer)
        transferredBytes += i
        self ! rbc
      } else {
        connection ! Tcp.Close
      }

    case _: Tcp.ConnectionClosed =>
      log.debug("{} <--> {} bytes", remote, transferredBytes)
      log.debug("Connection for remote address {} closed", remote)
      context.parent ! DataConnector.Success(transferredBytes, memstream.toByteArray)
      context.stop(self)

    case Terminated(`connection`) =>
      log.debug("Connection for remote address {} died", remote)
      context.stop(self)

    case Tcp.CommandFailed(_) =>
      log.debug("Connection for remote address {} failed", remote)
      context.stop(self)
  }

}
