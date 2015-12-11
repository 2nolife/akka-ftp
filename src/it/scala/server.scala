package com.coldcore.akkaftp.it
package server

import com.coldcore.akkaftp.Launcher
import akka.actor.ActorSystem
import com.coldcore.akkaftp.ftp.core.{Session, FtpState}
import com.coldcore.akkaftp.ftp.filesystem.{ListingFile, File, FileSystem}
import java.nio.channels.{Channels, WritableByteChannel, ReadableByteChannel}
import org.scalatest.Matchers

import scala.concurrent.duration._
import Utils._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

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

  override def login(session: Session) {
    val file = new MemoryFile("/")
    session.homeDir = file
    session.currentDir = file
  }

  var allFiles = Map.empty[String,MemoryFile] // (path, file)
  override def file(path: String, session: Session): File = {
    allFiles.getOrElse(path, {
      val file = new MemoryFile(path)
      allFiles = allFiles + (path -> file)
      file
    })
  }
}

class MemoryFile(val path: String) extends File {
  private[server] var out: ByteArrayOutputStream = _
  private[server] var fdata: Option[Array[Byte]] = _
  private[server] var fexists = false
  private[server] var fdirectory = false

  def data: Option[Array[Byte]] = {
    if (out != null) { // flush data that server wrote to the file
      fdata = Some(out.toByteArray)
      out = null
    }
    fdata
  }

  override def parent: Option[File] = ???
  override def rename(dst: File): Unit = ???
  override def mkdir(): Unit = ???
  override def delete(): Unit = ???

  override def write(append: Boolean): WritableByteChannel = {
    out = new ByteArrayOutputStream
    Channels.newChannel(out)
  }

  override def read(position: Long): ReadableByteChannel = {
    Channels.newChannel(new ByteArrayInputStream(data.get))
  }

  override def exists: Boolean = fexists
  override def listFiles: Seq[ListingFile] = ???
  override def listFile: Option[ListingFile] = ???
}

class CustomLauncher extends Launcher {
  override def createFtpState(system: ActorSystem): FtpState = {
    new CustomFtpState(system, hostname = "127.0.0.1", port = 2021, guest = true,
      usersdir = "ftp_home", externalIp = "127.0.0.1", pasvPorts = Seq(6001,6002,6003))
  }
}

class FtpServer extends Matchers {
  private var system: ActorSystem = _
  private val launcher = new CustomLauncher
  var ftpstate: FtpState = _

  def allFiles = ftpstate.fileSystem.asInstanceOf[MemoryFileSystem].allFiles

  def fileData(path: String): Array[Byte] = {
    allFiles should contain key path
    allFiles.get(path).get.data.get
  }

  def addFile(path: String, body: Array[Byte]) = {
    val file = new MemoryFile(path) { fexists = true; fdirectory = false; fdata = Some(body) }
    ftpstate.fileSystem.asInstanceOf[MemoryFileSystem].allFiles = allFiles + (path -> file)
  }
  def addDirectory(path: String) = {
    val file = new MemoryFile(path) { fexists = true; fdirectory = true }
    ftpstate.fileSystem.asInstanceOf[MemoryFileSystem].allFiles = allFiles + (path -> file)
  }

  def start() {
    println("Starting FTP server")
    system = ActorSystem("it-server")
    ftpstate = launcher.createFtpState(system)
    launcher.startFtpService(system, ftpstate)
    delay(1 second)
  }

  def stop() {
    println("Stopping FTP server")
    system.shutdown()
    delay(1 second)
  }
}