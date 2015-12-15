package com.coldcore.akkaftp.ftp
package command

import com.coldcore.akkaftp.ftp.core.{FtpState, Session}
import com.coldcore.akkaftp.ftp.core.Constants.EoL
import org.scalatest.{Matchers, WordSpec}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import com.coldcore.akkaftp.ftp.filesystem.{ListingFile, FileSystem, File}
import java.util.Date

class CommandsSpec extends WordSpec with MockitoSugar with Matchers {

  class GuestFtpState(override val guest: Boolean) extends FtpState(
    system = null,
    hostname = "",
    port = -1,
    guest,
    usersdir = "",
    externalIp = "",
    pasvPorts = Seq.empty)

  "UserCommand" should {
    "error on empty parameter" in {
      val reply = UserCommand("", mock[Session]).exec
      reply should have ('code (501))
    }
    "accept username" in {
      val reply = UserCommand("myname", mock[Session]).exec
      reply should have ('code (331))
    }
    "deny guest username" in {
      val session = new Session(null, new GuestFtpState(false), null)
      val reply = UserCommand("anonymous", session).exec
      reply should have ('code (332))
    }
    "accept guest username" in {
      val session = new Session(null, new GuestFtpState(true), null)
      val reply = UserCommand("anonymous", session).exec
      reply should have ('code (331))
    }
  }

  "MlstCommand" should {
    val (ftpstate, fs, session, cdir) = (mock[FtpState], mock[FileSystem], mock[Session], mock[File])
    when(ftpstate.fileSystem).thenReturn(fs)
    when(session.ftpstate).thenReturn(ftpstate)
    when(session.currentDir).thenReturn(cdir)
    when(cdir.path).thenReturn("/")
    when(cdir.exists).thenReturn(true)

    "error on non-existing path" in {
      val path = mock[File]
      when(fs.file("/abc", session)).thenReturn(path)
      when(path.exists).thenReturn(false)
      val reply = MlstCommand("/abc", session).exec
      reply should have ('code (450))
    }
    "list current directory" in {
      val lf = new ListingFile("ftp", true, "rwxrwxrwx", "cdeflp", 0, "/", "/", new Date(0))
      when(cdir.listFile).thenReturn(Some(lf))
      val reply = MlstCommand("", session).exec
      reply should have ('code (250))
      reply.text should equal (s"Listing /${EoL}perm=cdeflp;modify=19700101010000;type=cdir; /${EoL}End")
    }
    "list file with special symbols in it" in {
      val filename = """/dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'"""
      val file = mock[File]
      when(fs.file(filename, session)).thenReturn(file)
      when(file.exists).thenReturn(true)
      when(file.path).thenReturn(filename)
      val lf = new ListingFile("ftp", false, "rwxrwxrwx", "adfrw", 12, """chunked "special" 'name'""", filename, new Date(0))
      when(file.listFile).thenReturn(Some(lf))
      val reply = MlstCommand(filename, session).exec
      reply should have ('code (250))
      reply.text should equal (s"Listing ${filename}${EoL}perm=adfrw;modify=19700101010000;size=12;type=file; ${filename}${EoL}End")
    }
  }

}
