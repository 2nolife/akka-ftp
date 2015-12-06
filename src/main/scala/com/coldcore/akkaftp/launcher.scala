package com.coldcore.akkaftp

import akka.actor.ActorSystem
import com.coldcore.akkaftp.ftp.core.FtpState

object Launcher {

  def startFtpService(system: ActorSystem): FtpState = {
    import com.coldcore.akkaftp.ftp.core.Boot

    val hostname = Settings(system).hostname
    val portnumb = Settings(system).port
    val guest = Settings(system).guest
    val homedir = Settings(system).homedir
    val timeout = Settings(system).timeout

    val ftpstate = new FtpState(system, guest, homedir) { host = hostname; port = portnumb }
    Boot(ftpstate)
    ftpstate
  }

  def startRestService(system: ActorSystem, ftpstate: FtpState) {
    import com.coldcore.akkaftp.rest.core.Boot

    val hostname = Settings(system).restHostname
    val port = Settings(system).restPort

    Boot(hostname, port, ftpstate)
  }

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("akkaftp-system")

    val ftpstate = startFtpService(system)
    startRestService(system, ftpstate)

    sys.addShutdownHook {
      println("Shutting down ...")
      system.shutdown()
    }

    system.awaitTermination()
  }
}
