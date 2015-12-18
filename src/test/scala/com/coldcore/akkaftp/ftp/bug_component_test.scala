package com.coldcore.akkaftp.ftp
package test

import org.scalatest.{Matchers, WordSpec}
import org.scalatest.mock.MockitoSugar
import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.ftp.filesystem.{File, FileSystem}
import org.mockito.Mockito._
import com.coldcore.akkaftp.ftp.session.Session

class BugComponentSpec extends WordSpec with MockitoSugar with Matchers {

  "bugged component" should {
    val (ftpstate, fs, session, cdir) = (mock[FtpState], mock[FileSystem], mock[Session], mock[File])
    when(ftpstate.fileSystem).thenReturn(fs)
    when(session.ftpstate).thenReturn(ftpstate)
    when(session.currentDir).thenReturn(cdir)
    when(cdir.path).thenReturn("/")
    when(cdir.exists).thenReturn(true)

    "do expected stuff" in {
      // code your flow
    }
  }

}
