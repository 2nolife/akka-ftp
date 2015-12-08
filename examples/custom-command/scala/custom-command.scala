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

class CustomFtpState(system: ActorSystem, guest: Boolean, usersdir: String) extends FtpState(system, guest, usersdir) {
  override val commandFactory = new CustomCommandFactory
}

class CustomLauncher extends Launcher {
  override def createFtpState(system: ActorSystem): FtpState = {
    val hostname = Settings(system).hostname
    val portnumb = Settings(system).port
    val guest = Settings(system).guest
    val homedir = Settings(system).homedir

    new CustomFtpState(system, guest, homedir) { host = hostname; port = portnumb }
  }
}

object main {
  def main(args: Array[String]) {
    new CustomLauncher().start()
  }
}

// sbt "run-main examples.custom_command.main"
