package com.coldcore.akkaftp.ftp
package core

import java.net.{NetworkInterface, InetSocketAddress}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import com.coldcore.akkaftp.Util
import com.coldcore.akkaftp.ftp.command.{CommandFactory, DefaultCommandFactory}
import com.coldcore.akkaftp.ftp.connection.{DataConnector, ControlConnector}
import com.coldcore.akkaftp.ftp.datafilter.{SessionKeeper, TrafficCounter, DataFilterFactory, DataFilterApplicator}
import com.coldcore.akkaftp.ftp.executor.TaskExecutor
import com.coldcore.akkaftp.ftp.filesystem.{DiskFileSystem, FileSystem}
import com.coldcore.akkaftp.ftp.userstore.{PropsUserStore, UserStore}
import scala.collection.JavaConverters._
import com.coldcore.akkaftp.ftp.session.{SessionFactory, Session}

object Constants {
  val EoL = "\r\n"
  val UTF8 = "UTF-8"
}

object Boot {
  def apply(ftpstate: FtpState) = new Boot(ftpstate)
}

class Boot(ftpstate: FtpState) {
  def endpoint(hostname: String, port: Int): InetSocketAddress = hostname match {
    case "" => new InetSocketAddress(port)
    case _ => new InetSocketAddress(hostname, port)
  }
  val ep = endpoint(ftpstate.hostname, ftpstate.port)
  val system = ftpstate.system

  val executor = system.actorOf(TaskExecutor.props(8), name = "task-executor") //todo configurable # of exec nodes
  system.actorOf(ControlConnector.props(ep, ftpstate, executor), name = "ctrl-connector")
  system.actorOf(TrafficCounter.props(ftpstate.registry), name = "traffic-counter")
  system.actorOf(SessionKeeper.props(ftpstate.registry), name = "session-keeper")

  val dataConnector = system.actorOf(
    DataConnector.props(ep.getHostName, ftpstate.dataConnectorVars.ports, ftpstate.dataConnectorVars.remoteIpResolv),
    name = "data-connector")
  ftpstate.attributes.set(DataConnector.attr, dataConnector)
}

/** Application dependencies, overwrite to add your own */
class FtpState(val system: ActorSystem,
               val hostname: String, val port: Int,
               val guest: Boolean, val usersdir: String,
               val externalIp: String, val pasvPorts: Seq[Int]) { //todo implicit ftpstate and system
  val registry = new Registry
  val fileSystem: FileSystem = new DiskFileSystem(usersdir)
  val userStore: UserStore = new PropsUserStore(Util.readProperties("/userstore.properties"))
  val commandFactory: CommandFactory = new DefaultCommandFactory
  val sessionFactory = new SessionFactory
  val dataConnectorVars = new DataConnectorVars(pasvPorts, externalIp, hostname)
  val dataFilterApplicator = new DataFilterApplicator
  lazy val dataFilterFactory = new DataFilterFactory(fileSystem.endOfLine)

  var suspended = false
  val attributes = new CustomAttributes
}

class DataConnectorVars(val ports: Seq[Int], val externalIp: String, boundHostname: String) {
  val remoteIpResolv: String => String = { remoteip =>
    val ipaddresses =
      NetworkInterface.getNetworkInterfaces.asScala.flatMap { netint =>
        netint.getInetAddresses.asScala.map(_.getAddress.map(_ & 0xff).mkString("."))
      }
    if (boundHostname.nonEmpty) externalIp
    else // bound to all interfaces, pick the best match
      ipaddresses.collectFirst {
        case addr if remoteip == addr => addr
        case addr if addr.split("\\.").size == 4 && remoteip.startsWith(addr.split("\\.").take(2).mkString("", ".", ".")) => addr
      } getOrElse {
        externalIp
      }
  }
}

object ID {
  private val along = new AtomicLong(0)
  def next: Long = along.addAndGet(1)
}

class Registry {
  var (sessions, disconnected) = (List.empty[Session], List.empty[Session])

  def addSession(session: Session) =
    this.synchronized {
      sessions = session :: sessions
    }

  def remSession(session: Session) =
    this.synchronized {
      sessions = sessions diff Seq(session)
      disconnected = session :: disconnected
    }

  var (uploadByteSec, downloadByteSec, uploadedBytes, downloadedBytes) = (0L, 0L, 0L, 0L)
}

class CustomAttributes {
  private val m = new scala.collection.mutable.LinkedHashMap[String,Any]
  def set[T](key: String, value: T) = m += key -> value
  def get[T](key: String):Option[T] = m.get(key).map(_.asInstanceOf[T])
  def rem[T](key: String):Option[T] = m.remove(key).map(_.asInstanceOf[T])
}

sealed trait DataOpenerType
case object PortDOT extends DataOpenerType
case object PasvDOT extends DataOpenerType

sealed trait DataTransferMode
case object StorDTM extends DataTransferMode // read data from the user and save it into file
case object StouDTM extends DataTransferMode // same as STOR, only send "250" reply at the end of the transfer
case object RetrDTM extends DataTransferMode // read data from file and send it to user
case object ListDTM extends DataTransferMode // send directory content to user

object CommonActions {
  case class SessionAliveIN(session: Session)
  case class SessionAliveOUT(session: Session)
}