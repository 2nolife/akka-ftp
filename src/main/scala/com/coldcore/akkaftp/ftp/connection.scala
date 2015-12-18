package com.coldcore.akkaftp.ftp
package connection

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.Date

import akka.actor.{Terminated, _}
import akka.io.{IO, Tcp}
import akka.util.{ByteString, CompactByteString}
import com.coldcore.akkaftp.ftp.command.{Reply, _}
import com.coldcore.akkaftp.ftp.core.Constants._
import com.coldcore.akkaftp.ftp.core._
import com.coldcore.akkaftp.ftp.executor.TaskExecutor
import scala.annotation.tailrec
import akka.io.Tcp.NoAck
import scala.concurrent.duration.DurationInt
import akka.routing.{Broadcast, RoundRobinPool}
import com.coldcore.akkaftp.ftp.session.{DataExchangeEnd, DataExchangeStart, CtrlExchange, Session}

object ControlConnector {
  def props(endpoint: InetSocketAddress, ftpstate: FtpState, executor: ActorRef): Props =
    Props(new ControlConnector(endpoint, ftpstate, executor))
}

class ControlConnector(endpoint: InetSocketAddress, ftpstate: FtpState, executor: ActorRef) extends Actor with ActorLogging {
  IO(Tcp)(context.system) ! Tcp.Bind(self, endpoint)
  log.info(s"Bound Akka FTP to ${endpoint.getHostName}:${endpoint.getPort}")

  def receive = {
    case Tcp.Connected(remote, _) => // connected
      log.debug("Remote address {} connected", remote)
      sender ! Tcp.Register(context.actorOf(ControlConnection.props(remote, sender, ftpstate, executor), name = "conn-"+ID.next))
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object ControlConnection { //todo inactive timeout
  def props(remote: InetSocketAddress, connection: ActorRef, ftpstate: FtpState, executor: ActorRef): Props =
    Props(new ControlConnection(remote, connection, ftpstate, executor))

  case object Poison
}

class ControlConnection(remote: InetSocketAddress, connection: ActorRef, ftpstate: FtpState, executor: ActorRef) extends Actor with ActorLogging {
  import ControlConnection._
  implicit def Reply2ByteString(x: Reply): ByteString = CompactByteString(x.serialize)
  implicit def String2ByteString(x: String): ByteString = CompactByteString(x)

  case class Extracted(command: Command)
  case class Ack(command: Command) extends Tcp.Event

  class Buffer {
    val buffer = new StringBuilder
    def append(x: String) = buffer.append(x) //todo buffer overflow protection

    def extract: Option[String] = {
      val str = buffer.toString
      if (str.contains(EoL)) {
        buffer.delete(0, str.indexOf(EoL)+EoL.length)
        str.split(EoL).headOption
      } else None
    }
  }

  val session = ftpstate.sessionFactory.session(self, ftpstate, remote)
  val buffer = new Buffer
  var executing: Option[Command] = None

  context.watch(connection)
  ftpstate.registry.addSession(session)
  session.attributes.set("connected.date", new Date())

  if (ftpstate.suspended) executor ! UnavailableCommand(session)
  else executor ! WelcomeCommand(session)

  def close() {
    context.stop(self)
    ftpstate.registry.remSession(session)
  }

  def receive = {
    case Tcp.Received(data) => // user sends data
      buffer.append(data.utf8String)
      if (executing.isEmpty)
        buffer.extract match {
          case Some(text) => // execute the command
            log.debug("{} ---> {}", remote, text)
            session <-- CtrlExchange(text, userToServer = true)
            val command = ftpstate.commandFactory.create(text, session)
            self ! Extracted(command)
          case None => // or if the connection is poisoned
            if (session.poisoned) self ! Poison
        }

    case Extracted(command) => // execute the command now
      executing = Some(command)
      executor ! command

    case TaskExecutor.Executed(UnavailableCommand(_), reply) => // send 421 reply
      connection ! Tcp.Write(reply)
      log.debug("{} <--- {}", remote, reply.serialize.trim)
      session <-- CtrlExchange(reply.serialize, userToServer = false)
      connection ! Tcp.Close

    case TaskExecutor.Executed(command, reply) if reply.noop => // do not send a reply
      self ! Ack(command)

    case TaskExecutor.Executed(command, reply) => // send a reply (or replies if nested)
      @tailrec def write(command: Command, reply: Reply) {
        connection ! Tcp.Write(reply, if (reply.next.isEmpty) Ack(command) else NoAck)
        log.debug("{} <--- {}", remote, reply.serialize.trim)
        session <-- CtrlExchange(reply.serialize, userToServer = false)

        if (reply.code >= 100 && reply.code <= 199 ) { // code 1xx
          session.interruptState = true
          log.debug("{} interrupt state ON", remote)
        }
        command match {
          case x: Interrupt if x.replyClearsInterrupt && session.interruptState =>
            session.interruptState = false
            log.debug("{} interrupt state OFF", remote)
          case _ =>
        }
        if (reply.next.isDefined) write(command, reply.next.get)
      }
      write(command, reply)

    case Ack(command) => // OS acknowledges the reply was queued successfully
      if (executing == Some(command) || executing.isEmpty) {
        executing = None
        self ! Tcp.Received("") // loop to process the next command from the buffer
      }

    case Poison => // close the poisoned connection if idle
      session.poisoned = true
      if (executing.isEmpty && session.dataConnection.isEmpty)
        connection ! Tcp.Close

    case DataConnection.Success => // transfer successful
      executor ! TransferSuccessCommand(session)

    case DataConnection.Failed => // transfer failed
      executor ! TransferFailedCommand(session)

    case DataConnection.Aborted => // transfer aborted by the user
      executor ! TransferAbortedCommand(session)

    case CommonActions.SessionAliveIN(_) => // respond or receive Kill
      sender ! CommonActions.SessionAliveOUT(session)

    case _: Tcp.ConnectionClosed => // good
      log.debug("Connection for remote address {} closed", remote)
      close()

    case Terminated(`connection`) => // bad
      log.debug("Connection for remote address {} died", remote)
      close()

    case Tcp.CommandFailed(_) => // bad
      log.debug("Connection for remote address {} failed", remote)
      close()
  }
}

object DataConnectionInitiator {
  def props(endpoint: InetSocketAddress, session: Session): Props =
    Props(new DataConnectionInitiator(endpoint, session))
}

class DataConnectionInitiator(endpoint: InetSocketAddress, session: Session) extends Actor with ActorLogging {
  IO(Tcp)(context.system) ! Tcp.Connect(endpoint)

  def fail() {
    session.ctrl ! DataConnection.Failed // notify the control connection
    DataConnection.resetSession(session)
  }

  def receive = {
    case Tcp.Connected(remote, _) => // connected
      log.debug("Connected to remote address {}", remote)
      val dconref = context.actorOf(DataConnection.props(remote, sender, session), name = "conn-"+ID.next)
      sender ! Tcp.Register(dconref)
      context.watch(dconref)

    case Terminated(_) => // data connection stopped
      context.stop(self)

    case Tcp.CommandFailed(_: Tcp.Connect) => // cannot connect
      log.debug("Connection to remote endpoint {} failed", endpoint.getHostName+":"+endpoint.getPort)
      fail()
      context.stop(self)
  }

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _ => // bad
        context.stop(self)
        SupervisorStrategy.Stop
    }
}

object DataConnection { //todo inactive timeout
  def props(remote: InetSocketAddress, connection: ActorRef, session: Session): Props =
    Props(new DataConnection(remote, connection, session))

  case object Abort

  sealed trait ReportState
  case object Success extends ReportState
  case object Failed extends ReportState
  case object Aborted extends ReportState

  def resetSession(session: Session) {
    session.dataTransferChannel.foreach(_.safeClose())
    session.dataTransferChannel = None
    session.dataFilename = None
  }
}

class DataConnection(remote: InetSocketAddress, connection: ActorRef, session: Session) extends Actor with ActorLogging {
  import com.coldcore.akkaftp.ftp.connection.DataConnection._
  import context.dispatcher
  implicit def Buffer2ByteString(x: ByteBuffer): ByteString = CompactByteString(x)

  case object StartTransfer
  case object Write
  case object Ack extends Tcp.Event

  context.watch(connection)
  session.dataConnection = Some(self)

  val buffer = ByteBuffer.allocate(1024*8) // 8 KB buffer
  var report: Option[ReportState] = None
  var rbc: ReadableByteChannel = _
  var wbc: WritableByteChannel = _
  var transferredBytes = 0L

  self ! StartTransfer // check if ready and start data exchange

  val readReceive: Actor.Receive = {
    case Tcp.Received(data) => // read data from the user
      //log.debug("{} ---> {} bytes", remote, data.size)
      val b = data.asByteBuffer
      val r = b.remaining
      val i = wbc.write(b)
      if (i != r) throw new IllegalStateException(s"Corrupted channel write: $i != $r")
      transferredBytes += i
      session.ftpstate.registry.uploadedBytes += i
      session.uploadedBytes += i

    case Abort => // abort command from the user
      report = Some(Aborted)
      connection ! Tcp.Close

    case _: Tcp.ConnectionClosed => // good
      if (report.isEmpty) report = Some(Success)
      log.debug("{} ---> {} bytes", remote, transferredBytes)
      context.stop(self)
  }

  val writeReceive: Actor.Receive = {
    case x @ Write => // send a data chunk to the user
      buffer.clear()
      val i = rbc.read(buffer)
      buffer.flip()
      if (i != -1) {
        connection ! Tcp.Write(buffer, Ack)
        //log.debug("{} <--- {} bytes", remote, i)
        transferredBytes += i
        session.ftpstate.registry.downloadedBytes += i
        session.downloadedBytes += i
      } else {
        report = Some(Success)
        connection ! Tcp.Close
      }

    case Ack => // OS acknowledges the data chunk was queued successfully
      self ! Write // loop to send the next chunk of data

    case Abort => // abort command from the user
      report = Some(Aborted)
      connection ! Tcp.Close

    case _: Tcp.ConnectionClosed => // good
      log.debug("{} <--- {} bytes", remote, transferredBytes)
      context.stop(self)
  }

  val defReceive: Actor.Receive = {
    case Terminated(`connection`) => // bad
      log.debug("Connection for remote address {} died", remote)
      context.stop(self)

    case Tcp.CommandFailed(_) => // bad
      log.debug("Connection for remote address {} failed", remote)
      context.stop(self)

    case StartTransfer if session.dataTransferMode.isDefined && session.dataTransferChannel.isDefined => // start transfer now
      log.debug("Starting data trasfer with {}", remote)
      session.dataTransferMode.get match {
        case StorDTM | StouDTM => // write from the channel source to a client
          context.become(readReceive orElse defReceive)
          wbc = session.dataTransferChannel.get.asInstanceOf[WritableByteChannel]
          if (session.dataFilename.isDefined)
            session <-- DataExchangeStart(session.dataFilename.getOrElse(""), userToServer = false)
        case RetrDTM | ListDTM => // read from a client into the channel dest
          context.become(writeReceive orElse defReceive)
          rbc = session.dataTransferChannel.get.asInstanceOf[ReadableByteChannel]
          if (session.dataFilename.isDefined)
            session <-- DataExchangeStart(session.dataFilename.getOrElse(""), userToServer = true)
          self ! Write
      }

    case StartTransfer => // reschedule, not ready (client did not send a command yet)
      context.system.scheduler.scheduleOnce(100.milliseconds, self, StartTransfer)
  }

  override def postStop() {
    session.ctrl ! report.getOrElse(Failed) // notify the control connection
    log.debug("Closing connection to remote address {}", remote)
    if (session.dataFilename.isDefined)
      session <-- DataExchangeEnd(session.dataFilename.getOrElse(""), userToServer = rbc != null,
                                  success = report == Some(Success))
    resetSession(session)
    super.postStop()
  }

  def receive = defReceive
}

object DataConnector {
  def props(hostname: String, ports: Seq[Int], ipresolv: String => String): Props =
    Props(new DataConnector(hostname, ports, ipresolv))

  case class Accept(session: Session)
  case class Accepted(ipaddr:String, port: Int)
  case object Rejected
  case class Cancel(session: Session)

  val attr = "DataConnector.actorRef"

  class PortsIterator(ports: Seq[Int]) {
    val pt = ports.iterator
    def next: Int = this.synchronized { pt.next() }
  }
}

class DataConnector(hostname: String, ports: Seq[Int], ipresolv: String => String) extends Actor with ActorLogging {
  import DataConnector._

  val nodes = {
    val pt = new PortsIterator(ports)
    context.actorOf(DataConnectorNode.props(hostname, pt).withRouter(RoundRobinPool(ports.size)), name = "node")
  }

  def receive = {
    case Accept(session) => // accept a new connection
      if (ports.isEmpty) {
        DataConnection.resetSession(session)
        sender ! Rejected
      } else {
        nodes ! DataConnectorNode.Reserve(session, attempt = 1, sender)
      }

    case DataConnectorNode.Fail(session, attempt, owner) => // node says no
      if (attempt > ports.size*2) {
        session.ctrl ! DataConnection.Failed // notify the control connection
        DataConnection.resetSession(session)
        owner ! Rejected
      } else {
        nodes ! DataConnectorNode.Reserve(session, attempt+1, owner)
      }

    case DataConnectorNode.Success(session, port, owner) => // node says yes
      val ipaddr = ipresolv(session.remote.getAddress.getAddress.map(_ & 0xff).mkString("."))
      owner ! Accepted(ipaddr, port)

    case x @ Cancel(session) => // cancel reservation for a connection if any
      nodes ! Broadcast(x)
  }
}

object DataConnectorNode {
  def props(hostname: String, pt: DataConnector.PortsIterator): Props =
    Props(new DataConnectorNode(hostname, pt))

  case class Reserve(session: Session, attempt: Int, owner: ActorRef)
  case class Fail(session: Session, attempt: Int, owner: ActorRef)
  case class Success(session: Session, port: Int, owner: ActorRef)
}

class DataConnectorNode(hostname: String, pt: DataConnector.PortsIterator) extends Actor with ActorLogging {
  import DataConnectorNode._
  import context.dispatcher

  val endpoint = new InetSocketAddress(hostname, pt.next)
  IO(Tcp)(context.system) ! Tcp.Bind(self, endpoint)
  log.info(s"Bound data connector to ${endpoint.getHostName}:${endpoint.getPort}")

  val reserved = new collection.mutable.HashMap[String,Session] // (remote host, session)
  val timeoutSch = "DataConnectorNode.timeoutSch"

  def fail(session: Session) {
    session.ctrl ! DataConnection.Failed // notify the control connection
    DataConnection.resetSession(session)
  }

  def ip(remote: InetSocketAddress) = remote.getAddress.getAddress.map(_ & 0xff).mkString(".")

  def receive = {
    case Tcp.Connected(remote, _) if reserved.contains(ip(remote)) => // connected
      log.debug("Remote address {} connected", remote)
      val session = reserved.remove(ip(remote)).get
      session.attributes.get[Cancellable](timeoutSch).foreach(_.cancel())
      sender ! Tcp.Register(context.actorOf(DataConnection.props(remote, sender, session), name = "conn-"+ID.next))

    case Tcp.Connected(remote, _) => // connected
      log.debug("Unrecognised remote IP {} connected", ip(remote))
      sender ! Tcp.Close //todo will this work?

    case Reserve(session, attempt, owner) if reserved.contains(ip(session.remote)) => // spot already taken
      sender ! Fail(session, attempt, owner)

    case Reserve(session, _, owner) => // reserve a spot for a connection
      reserved.put(ip(session.remote), session)
      val x = context.system.scheduler.scheduleOnce(15.seconds, self, DataConnector.Cancel(session))
      session.attributes.set(timeoutSch, x)
      sender ! Success(session, endpoint.getPort, owner)

    case DataConnector.Cancel(session) if reserved.contains(ip(session.remote)) => // cancel reservation
      reserved.remove(ip(session.remote))
      session.attributes.get[Cancellable](timeoutSch).foreach(_.cancel())
      fail(session)

    case Broadcast(x) =>
      self ! x
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}
