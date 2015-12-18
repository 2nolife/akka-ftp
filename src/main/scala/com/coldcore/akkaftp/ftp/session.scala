package com.coldcore.akkaftp.ftp
package session

import akka.actor.ActorRef
import java.net.InetSocketAddress
import java.nio.channels.Channel
import com.coldcore.akkaftp.ftp.filesystem.File
import com.coldcore.akkaftp.ftp.core._
import java.util.Date

class Session(val ctrl: ActorRef, val ftpstate: FtpState, val remote: InetSocketAddress) extends EventListener {
  val id = ID.next

  var username: Option[String] = None
  var password: Option[String] = None
  var loggedIn = false

  var interruptState = false
  var poisoned = false

  var dataType = "A"
  var dataMode = "S"
  var dataStructure = "F"
  var dataOpenerType: Option[DataOpenerType] = None
  var dataEndpoint: Option[InetSocketAddress] = None
  var dataMarker = 0L

  var dataTransferMode: Option[DataTransferMode] = None
  var dataTransferChannel: Option[Channel] = None
  var dataFilename: Option[String] = None

  var dataConnection: Option[ActorRef] = None

  var homeDir: File = _
  var currentDir: File = _

  def login() {
    loggedIn = true
    ftpstate.fileSystem.login(this)
    require(homeDir != null, "Home dir not set")
    require(currentDir != null, "Current dir not set")
  }

  def guest = username.exists("anonymous"==)

  val attributes = new CustomAttributes

  var (uploadedBytes, downloadedBytes) = (0L, 0L)

  val chistoryKey = "History.CtrlExchange"
  val dhistoryKey = "History.DataExchange"
  attributes.set(chistoryKey, List.empty[CtrlExchange])
  attributes.set(dhistoryKey, List.empty[DataExchange])

  val defaultEventHandler: PartialFunction[Any,Unit] = {
    case x @ CtrlExchange(_, userToServer) => // control connection event
      attributes.get[List[CtrlExchange]](chistoryKey).map(xs => attributes.set(chistoryKey, x :: xs))
      if (userToServer) attributes.set("lastCommand.date", new Date())

    case x @ (DataExchangeStart(_, _) | DataExchangeEnd(_, _, _)) => // data connection event
      attributes.get[List[DataExchange]](dhistoryKey).map(xs => attributes.set(dhistoryKey, x :: xs))

    case Tick => // session maintenance event
      onTick()
  }

  /** overwrite to add your own events: def eventHandler = myEventHandler orElse defaultEventHandler */
  def eventHandler = defaultEventHandler

  def onTick() {
    { val xs = attributes.get[List[CtrlExchange]](chistoryKey).get
      if (xs.size > 1000) attributes.set(chistoryKey, xs.slice(0, 1000)) }
    { val xs = attributes.get[List[DataExchange]](dhistoryKey).get
      if (xs.size > 1000) attributes.set(dhistoryKey, xs.slice(0, 1000)) }
  }
}

/** Overwrite this if you need to provide your own session implementation */
class SessionFactory {
  def session(ctrl: ActorRef, ftpstate: FtpState, remote: InetSocketAddress) = new Session(ctrl, ftpstate, remote)
}

case class CtrlExchange(text: String, userToServer: Boolean)

trait DataExchange
case class DataExchangeStart(filename: String, userToServer: Boolean) extends DataExchange
case class DataExchangeEnd(filename: String, userToServer: Boolean, success: Boolean) extends DataExchange

case object Tick

trait EventListener {
  def eventHandler: PartialFunction[Any,Unit]
  def <--(event: Any) = eventHandler.apply(event)
}
