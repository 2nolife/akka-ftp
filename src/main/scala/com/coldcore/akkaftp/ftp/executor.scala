package com.coldcore.akkaftp.ftp
package executor

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.routing.RoundRobinPool
import com.coldcore.akkaftp.ftp.command._

object TaskExecutor {
  def props(n: Int): Props = Props(new TaskExecutor(n))

  case class Executed(command: Command, reply: Reply)
}

class TaskExecutor(n: Int) extends Actor with ActorLogging {
  import com.coldcore.akkaftp.ftp.executor.TaskExecutor._

  val nodes = context.actorOf(Props[TaskNode]. withRouter(RoundRobinPool(n)), name = "node")

  def receive = {
    case command: Command => // execute the command
      nodes ! TaskNode.Execute(command, sender)

    case TaskNode.Executed(command, reply, owner) => // command executed
      owner ! Executed(command, reply)
  }
}

object TaskNode {
  case class Execute(command: Command, owner: ActorRef)
  case class Executed(command: Command, reply: Reply, owner: ActorRef)
}

class TaskNode extends Actor with ActorLogging { //todo execution timeout
  import com.coldcore.akkaftp.ftp.executor.TaskNode._

  def receive = {
    case Execute(command, owner) => // execute the command
      val session = command.session
      val reply =
        try {
          command match {
            case UnavailableCommand(_) => command.exec
            case x: Interrupt if session.interruptState || session.poisoned => command.exec
            case _ if session.interruptState || session.poisoned => CommonReplies.noop
            case _: LoggedIn if !session.loggedIn => CommonReplies.notLoggedIn
            case _ => command.exec
          }
        } catch {
          case e: Throwable =>
            log.error(e, "Error executing command {}", command)
            CommonReplies.localError
        }
      sender ! Executed(command, reply, owner)
  }
}

