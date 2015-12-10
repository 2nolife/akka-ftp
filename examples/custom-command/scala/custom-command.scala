package examples.custom_command

import com.coldcore.akkaftp.ftp.core.{FtpState, Session}
import com.coldcore.akkaftp.ftp.command.{DefaultCommandFactory, Reply, Command}
import java.text.SimpleDateFormat
import java.util.Date
import akka.actor.ActorSystem
import com.coldcore.akkaftp.{Settings, Launcher}

case class XTimeCommand(param: String, session: Session) extends Command {
  override def exec: Reply = {
    val sdf = new SimpleDateFormat(if (param.isEmpty) "dd/MM/yyyy HH:mm:ss" else param)
    val date = sdf.format(new Date)
    Reply(200, s"Server time $date")
  }
}

class CustomCommandFactory extends DefaultCommandFactory {
  def mycmd(name: String, param: String, session: Session): Option[Command] =
    Option(name match {
      case "X-TIME" => XTimeCommand(param, session)
      case _ => null
    })

  override def cmd(name: String, param: String, session: Session): Command =
    mycmd(name, param, session) getOrElse super.cmd(name, param, session)
}

class CustomFtpState(override val system: ActorSystem,
                     override val hostname: String,
                     override val port: Int,
                     override val guest: Boolean,
                     override val usersdir: String,
                     override val externalIp: String,
                     override val pasvPorts: Seq[Int]) extends
  FtpState(system, hostname, port, guest, usersdir, externalIp, pasvPorts) {
    override val commandFactory = new CustomCommandFactory
}

class CustomLauncher extends Launcher {
  override def createFtpState(system: ActorSystem): FtpState = {
    val hostname = Settings(system).hostname
    val port = Settings(system).port
    val guest = Settings(system).guest
    val homedir = Settings(system).homedir
    val externalIp = Settings(system).externalIp
    val pasvPorts = Settings(system).pasvPorts

    new CustomFtpState(system, hostname, port, guest, homedir, externalIp, pasvPorts)
  }
}

object main {
  def main(args: Array[String]) {
    new CustomLauncher().start()
  }
}

// sbt "run-main examples.custom_command.main"
