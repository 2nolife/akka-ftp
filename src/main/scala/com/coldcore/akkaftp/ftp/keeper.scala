package com.coldcore.akkaftp.ftp
package datafilter

import akka.actor._
import com.coldcore.akkaftp.ftp.core.{CommonActions, Registry}

import scala.concurrent.duration.DurationInt
import com.coldcore.akkaftp.ftp.session.Session
import akka.routing.RoundRobinPool

object TrafficCounter {
  def props(registry: Registry): Props = Props(new TrafficCounter(registry))
}

class TrafficCounter(registry: Registry) extends Actor with ActorLogging {
  import context.dispatcher

  case object Tick

  context.system.scheduler.schedule(1.second, 1.second, self, Tick)

  var (uploadedBytes, downloadedBytes) = (0L, 0L)
  val bps = (bytes: Long) => math.round(bytes/1000d)

  def receive = {
    case Tick =>
      val (rup, rdown) = (registry.uploadedBytes, registry.downloadedBytes)
      if (uploadedBytes == 0) uploadedBytes = rup
      else {
        registry.uploadByteSec = bps(rup-uploadedBytes)
        uploadedBytes = rup
      }
      if (downloadedBytes == 0) downloadedBytes = rdown
      else {
        registry.downloadByteSec = bps(rdown-downloadedBytes)
        downloadedBytes = rdown
      }
  }
}

object SessionKeeper {
  def props(registry: Registry): Props = Props(new SessionKeeper(registry))

}

class SessionKeeper(registry: Registry) extends Actor with ActorLogging {
  import context.dispatcher

  case object Tick

  val nodes = context.actorOf(SessionKeeperNode.props(registry).withRouter(RoundRobinPool(4)), name = "node")

  context.system.scheduler.schedule(10.second, 10.second, self, Tick)

  var dead = collection.mutable.HashSet.empty[Session]

  def receive = {
    case Tick =>
      if (dead.size > 0) { // kill all dead sessions
        log.warning(s"Terminating ${dead.size} dead sessions")
        dead.foreach { session =>
          registry.remSession(session)
          session.ctrl ! Kill
        }
        dead.clear()
      } else { // mark all sessions as dead and send "alive" message
        registry.sessions.foreach { session =>
          dead += session
          session.ctrl ! CommonActions.SessionAliveIN(session)
        }
      }

      registry.sessions.foreach(nodes ! SessionKeeperNode.TickEvent(_))

    case CommonActions.SessionAliveOUT(session) => // session is alive
      dead -= session
  }
}

object SessionKeeperNode {
  def props(registry: Registry): Props = Props(new SessionKeeperNode(registry))

  case class TickEvent(session: Session)
}

class SessionKeeperNode(registry: Registry) extends Actor with ActorLogging {
  import SessionKeeperNode._
  import session.{Tick => STick}

  def receive = {
    case TickEvent(session) => // session maintenance event
      session <-- STick
  }
}

