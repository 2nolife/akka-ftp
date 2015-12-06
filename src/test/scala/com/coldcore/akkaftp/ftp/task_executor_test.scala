package com.coldcore.akkaftp.ftp
package connection

import com.coldcore.akkaftp.ftp.core.Session
import com.coldcore.akkaftp.ftp.executor.TaskExecutor
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.coldcore.akkaftp.ftp.command.UserCommand

class TaskExecutorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
with FlatSpecLike with MockitoSugar with BeforeAndAfterAll with Matchers {

  def this() = this(ActorSystem("MySpec"))

  override protected def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  it should "execute a command and return the same command" in {
    val session = mock[Session]
    val executor = system.actorOf(TaskExecutor.props(2))
    val command = UserCommand("", session)

    executor ! command

    expectMsgType[TaskExecutor.Executed] match {
      case TaskExecutor.Executed(cmd, _) => cmd should be (command)
      case x => fail(s"Received $x")
    }
  }

}
