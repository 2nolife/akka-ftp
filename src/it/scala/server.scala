package com.coldcore.akkaftp.it
package server

import com.coldcore.akkaftp.Launcher
import akka.actor.ActorSystem
import com.coldcore.akkaftp.ftp.core.{Session, FtpState}
import com.coldcore.akkaftp.ftp.filesystem.{File, FileSystem}

class CustomFtpState(override val system: ActorSystem,
                     override val hostname: String,
                     override val port: Int,
                     override val guest: Boolean,
                     override val usersdir: String,
                     override val externalIp: String,
                     override val pasvPorts: Seq[Int]) extends
  FtpState(system, hostname, port, guest, usersdir, externalIp, pasvPorts) {
  override val fileSystem = new MemoryFileSystem
}

class MemoryFileSystem extends FileSystem {
  override def logout(session: Session) {}
  override def login(session: Session) {}
  override def file(path: String, session: Session): File = ???
}

class CustomLauncher extends Launcher {
  var ftpstate: FtpState = _
  var system: ActorSystem = _

  override def createFtpState(system: ActorSystem): FtpState = {
    new CustomFtpState(system, hostname = "127.0.0.1", port = 2021, guest = true,
      usersdir = "ftp_home", externalIp = "127.0.0.1", pasvPorts = Seq(6001,6002,6003))
  }

  override def startFtpService(system: ActorSystem, ftpstate: FtpState) {
    import com.coldcore.akkaftp.ftp.core.Boot
    Boot(ftpstate)
  }


  override def start() {
    system = ActorSystem("it-server-system")
    ftpstate = createFtpState(system)
    startFtpService(system, ftpstate)
  }

  def stop() {
    system.shutdown()
  }

}
