package com.coldcore.akkaftp.it
package server

import java.util.Date

import com.coldcore.akkaftp.Launcher
import akka.actor.ActorSystem
import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.ftp.filesystem.{ListingFile, File, FileSystem}
import java.nio.channels.{Channels, WritableByteChannel, ReadableByteChannel}
import com.coldcore.akkaftp.ftp.userstore.UserStore
import org.scalatest.Matchers

import scala.concurrent.duration._
import Utils._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.text.SimpleDateFormat
import com.coldcore.akkaftp.ftp.session.Session

class CustomFtpState(override val system: ActorSystem,
                     override val hostname: String,
                     override val port: Int,
                     override val guest: Boolean,
                     override val usersdir: String,
                     override val externalIp: String,
                     override val pasvPorts: Seq[Int]) extends
  FtpState(system, hostname, port, guest, usersdir, externalIp, pasvPorts) {
  override val fileSystem = new MemoryFileSystem
  override val userStore = new DummyUserStore
}

class MemoryFileSystem extends FileSystem {
  override def logout(session: Session) {}

  override def login(session: Session) {
    val dir = allFiles.getOrElse("/", new MemoryFile("/", this) { fexists = true; fdirectory = true })
    allFiles = allFiles + ("/" -> dir)
    session.homeDir = dir
    session.currentDir = dir
  }

  var allFiles = Map.empty[String,MemoryFile] // (path, file)
  override def file(path: String, session: Session): File = {
    allFiles.getOrElse(path, {
      val file = new MemoryFile(path, this)
      allFiles = allFiles + (path -> file)
      file
    })
  }
}

class MemoryFile(val path: String, fs: MemoryFileSystem) extends File {
  private[server] var out: ByteArrayOutputStream = _
  private[server] var fdata = Array.empty[Byte]
  private[server] var fexists = false
  private[server] var fdirectory = false
  private[server] var fmodified = new Date

  def data: Array[Byte] = {
    if (out != null) { // flush data that server wrote to the file
      fdata = out.toByteArray
      out = null
    }
    fdata
  }

  override def parent: Option[File] = {
    if (path == "/") None
    else {
      val x = {
        val a = path.reverse.dropWhile('/'!=).drop(1).reverse
        if (a.isEmpty) "/" else a
      }
      fs.allFiles.get(x)
    }
  }

  override def rename(dst: File) {
    fexists = false
    fs.allFiles = fs.allFiles + (dst.path -> dst.asInstanceOf[MemoryFile])
  }

  override def mkdir() {
    val file = new MemoryFile(path, fs) { fexists = true; fdirectory = true }
    fs.allFiles = fs.allFiles + (path -> file)
  }
  override def delete() = fexists = false

  override def write(append: Boolean): WritableByteChannel = {
    out = new ByteArrayOutputStream
    if (append) out.write(fdata)
    Channels.newChannel(out)
  }

  override def read(position: Long): ReadableByteChannel = {
    val in = new ByteArrayInputStream(data)
    (1 to position.toInt).foreach(_ => in.read)
    Channels.newChannel(in)
  }

  override def exists: Boolean = fexists

  override def listFiles: Seq[ListingFile] =
    if (!fexists) Seq.empty[ListingFile]
    else if (!fdirectory) Seq(listFile.get)
    else fs.allFiles.filter { case (p,_) =>
      val x = if (path == "/") "/" else path+"/"
      p.startsWith(x) && p.count('/'==) == x.count('/'==) && p != "/"
    }.flatMap(_._2.listFile).toSeq

  override def listFile: Option[ListingFile] =
    if (!fexists) None
    else Some(new ListingFile("ftp", fdirectory, "rwxrwxrwx", if (fdirectory) "cdeflp" else "adfrw",
      data.size, path.split("/").lastOption.getOrElse("/"), path, fmodified))
}

class DummyUserStore extends UserStore {
  override def login(username: String, password: String): Boolean = username == password
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

  private lazy val fs = ftpstate.fileSystem.asInstanceOf[MemoryFileSystem]

  def fileData(path: String): Array[Byte] = {
    fs.allFiles should contain key path
    fs.allFiles(path).data
  }

  def addFile(path: String, body: Array[Byte], modified: Date = new Date) = {
    val file = new MemoryFile(path, fs) { fexists = true; fdirectory = false; fdata = body; fmodified = modified }
    fs.allFiles = fs.allFiles + (path -> file)
  }

  def addDirectory(path: String, modified: Date = new Date) = {
    val file = new MemoryFile(path, fs) { fexists = true; fdirectory = true; fmodified = modified }
    fs.allFiles = fs.allFiles + (path -> file)
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
    system.terminate()
    delay(1 second)
  }
}

trait CreateSampleFiles {
  val server: FtpServer

  implicit def String2Bytes(x: String): Array[Byte] = x.getBytes
  val mod = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("02/12/2014 22:34:56")
  val ad = server.addDirectory(_: String, mod)
  val af = server.addFile(_: String, _: Array[Byte], mod)

  def createSampleFiles() {
    // root
    ad("/")

    // directories
    ad("/dirA")
    ad("/dirA/dir1")
    ad("/dirA/dir2")
    ad("/dirB")
    ad("/dirB/dir1")
    ad("/dirB/dir1/dir2")
    ad("""/dirB/dir1/dir2/dir "3" 4""")

    // root files
    af("/abc.txt", "abc")
    af("/qwerty.txt", "qwerty")

    // files in dirA (simple file names with unix/win line feeds)
    af("/dirA/digits10.dat", "1234567890")
    af("/dirA/digits15.dat", "123456789012345")
    af("/dirA/dir1/symbols.12", "qwertyuiop12")
    af("/dirA/dir1/symbols.15", "23-qwertyuiop12")
    af("/dirA/dir1/empty.txt", "")
    af("/dirA/dir2/multiline-unix.txt", "\nline1\nline2\n\nline3\n")
    af("/dirA/dir2/multiline-win.txt", "\r\nlineA\r\nlineB\r\n\r\nlineC\r\n")
    af("/dirA/dir2/multiline-mix.txt", "\r\nlineA\nline2\r\n\r\nline4\n")
    af("/dirA/dir2/multiline-mix-empty.txt", "\r\n\n\r\n\r\n\n")

    // files in dirB (weird file names with weird content) content may change in the future
    af("/dirB/dir1/chunk C", "randomdata-3")
    af("/dirB/dir1/CHUNK C", "randomdata-4")
    af("/dirB/dir1/dir2/chunked random data long name", "randomdata-5")
    af("/dirB/dir1/dir2/c", "randomdata-6")
    af("""/dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""", "randomdata-7")
  }
}
