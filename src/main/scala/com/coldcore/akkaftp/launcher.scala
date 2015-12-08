package com.coldcore.akkaftp

import akka.actor.ActorSystem
import com.coldcore.akkaftp.ftp.core.FtpState

class Launcher {
  def createFtpState(system: ActorSystem): FtpState = {
    val hostname = Settings(system).hostname
    val portnumb = Settings(system).port
    val guest = Settings(system).guest
    val homedir = Settings(system).homedir
    val timeout = Settings(system).timeout

    new FtpState(system, guest, homedir) { host = hostname; port = portnumb }
  }

  def startFtpService(system: ActorSystem, ftpstate: FtpState) {
    import com.coldcore.akkaftp.ftp.core.Boot
    Boot(ftpstate)
  }

  def startRestService(system: ActorSystem, ftpstate: FtpState) {
    import com.coldcore.akkaftp.rest.core.Boot

    val hostname = Settings(system).restHostname
    val port = Settings(system).restPort

    Boot(hostname, port, ftpstate)
  }

  def start() {
    val system = ActorSystem("akkaftp-system")

    val ftpstate = createFtpState(system)
    startFtpService(system, ftpstate)
    startRestService(system, ftpstate)

    sys.addShutdownHook {
      println("Shutting down ...")
      system.shutdown()
    }

    system.awaitTermination()
  }
}

object main {
  def main(args: Array[String]) {
    new Launcher().start()
  }
}