package com.coldcore.akkaftp.it
package test


import com.coldcore.akkaftp.it.client.FtpClient
import com.coldcore.akkaftp.it.server.{CreateSampleFiles, FtpServer}
import org.scalatest._
import Utils._
import scala.concurrent.duration._
import com.coldcore.akkaftp.ftp.core.Constants.EoL

class LoginLogoutSpec extends WordSpec with BeforeAndAfterAll with Matchers {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    client.connect()
  }

  override protected def afterAll() {
    client.disconnect()
    server.stop()
  }

  // login
  "USER and PASS commands" should {
    "error on empty username" in {
      ((client <-- "USER") code) should be (501)
    }
    "error on PASS without username" in {
      ((client <-- "PASS mypass") code) should be (503)
    }
    "accept username" in {
      ((client <-- "USER myuser") code) should be (331)
    }
    "error on empty password" in {
      ((client <-- "PASS") code) should be (501)
    }
    "error on invalid password" in {
      ((client <-- "PASS mypass") code) should be (530)
    }
    "accept valid password" in {
      ((client <-- "PASS myuser") code) should be (230)
    }
    "error on USER after login" in {
      ((client <-- "USER foo") code) should be (503)
    }
    "error on PASS after login" in {
      ((client <-- "PASS foo") code) should be (503)
    }
  }

  // logout
  "QUIT command" should {
    "log user out and disconnect" in {
      ((client <-- "QUIT") code) should be (221)
      delay(100 milliseconds)
      client.connected should be (false)
    }
  }

}

class BrowserPortPasvSpec extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    createSampleFiles()
    client.connect()
    client.login("myuser", "myuser")
  }

  override protected def afterAll() {
    client.disconnect()
    server.stop()
  }

  // browse directories
  "PWD and CWD and CDUP commands" should {
    "tell current directory" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/" """ trim)
    }
    "do nothing on no parameter" in {
      ((client <-- "CWD") code) should be (250)
    }
    "change to existing directory" in {
      ((client <-- "CWD /dirA/dir1") code) should be (250)
    }
    "error on change to non-existing directory" in {
      ((client <-- "CWD /foo/bar") code) should be (450)
    }
    "tell current directory after change" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/dirA/dir1" """ trim)
    }
    "go up to parent directory" in { // /dirA
      ((client <-- "CDUP") code) should be (250)
    }
    "go up to parent directory again" in { // /
      ((client <-- "CDUP") code) should be (250)
    }
    "tell that current directory is /" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/" """ trim)
    }
    "go up to parent directory on /" in {
      ((client <-- "CDUP") code) should be (250)
    }
    "tell that current directory is still /" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/" """ trim)
    }
    "change to relative existing directory" in {
      ((client <-- "CWD dirB") code) should be (250)
    }
    "tell current directory after change to relative directory" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/dirB" """ trim)
    }
    "change to relative existing directory with quotes in it" in {
      ((client <-- """CWD dir1/dir2/dir "3" 4""") code) should be (250)
    }
    "tell current directory with quotes encoded" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/dirB/dir1/dir2/dir ""3"" 4" """ trim)
    }
    "change back to /" in {
      ((client <-- "CWD /") code) should be (250)
    }
    "verify that current directory is /" in {
      val reply = client <-- "PWD"
      (reply code) should be (257)
      (reply text) should include (""" "/" """ trim)
    }
  }

  // list directory
  "LIST command" should {
    "list current directory" in {
      client.cwd("/dirA")
      client.portMode()
      val (n, text) = client.list("LIST")
      val expected =
        "d rwxrwxrwx 1 ftp 0 Dec 02 22:34 dir1" ::
        "d rwxrwxrwx 1 ftp 0 Dec 02 22:34 dir2" ::
        "- rwxrwxrwx 1 ftp 10 Dec 02 22:34 digits10.dat" ::
        "- rwxrwxrwx 1 ftp 15 Dec 02 22:34 digits15.dat" :: Nil
      text.split(EoL) should have size 4
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list specific directory" in {
      client.pasvMode()
      val (n, text) = client.list("LIST /")
      val expected =
        "d rwxrwxrwx 1 ftp 0 Dec 02 22:34 dirA" ::
        "d rwxrwxrwx 1 ftp 0 Dec 02 22:34 dirB" ::
        "- rwxrwxrwx 1 ftp 3 Dec 02 22:34 abc.txt" ::
        "- rwxrwxrwx 1 ftp 6 Dec 02 22:34 qwerty.txt" :: Nil
      text.split(EoL) should have size 4
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list relative directory" in {
      client.pasvMode()
      val (n, text) = client.list("LIST dir1")
      val expected =
        "- rwxrwxrwx 1 ftp 12 Dec 02 22:34 symbols.12" ::
        "- rwxrwxrwx 1 ftp 15 Dec 02 22:34 symbols.15" ::
        "- rwxrwxrwx 1 ftp 0 Dec 02 22:34 empty.txt" :: Nil
      text.split(EoL) should have size 3
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list file" in {
      client.pasvMode()
      val (n, text) = client.list("LIST dir1/symbols.12")
      val expected =
        "- rwxrwxrwx 1 ftp 12 Dec 02 22:34 symbols.12" :: Nil
      text.split(EoL) should have size 1
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list file with special symbols in it" in {
      client.pasvMode()
      val (n, text) = client.list("""LIST /dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""")
      val expected =
        """- rwxrwxrwx 1 ftp 12 Dec 02 22:34 chunked "special" 'name'""" :: Nil
      text.split(EoL) should have size 1
      text.split(EoL) should contain theSameElementsAs expected
    }
  }

  // list directory
  "NLST command" should {
    "list current directory" in {
      client.cwd("/dirA")
      client.portMode()
      val (n, text) = client.list("NLST")
      val expected =
        "dir1/" ::
        "dir2/" ::
        "digits10.dat" ::
        "digits15.dat" :: Nil
      text.split(EoL) should have size 4
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list specific directory" in {
      client.pasvMode()
      val (n, text) = client.list("NLST /")
      val expected =
        "dirA/" ::
        "dirB/" ::
        "abc.txt" ::
        "qwerty.txt" :: Nil
      text.split(EoL) should have size 4
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list file with special symbols in it" in {
      client.pasvMode()
      val (n, text) = client.list("""NLST /dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""")
      val expected =
        """chunked "special" 'name'""" :: Nil
      text.split(EoL) should have size 1
      text.split(EoL) should contain theSameElementsAs expected
    }
  }

  // list directory
  "MLSD command" should {
    "list current directory" in {
      client.cwd("/dirA")
      client.portMode()
      val (n, text) = client.list("MLSD")
      val expected =
        "perm=cdeflp;modify=20141202223456;type=pdir; /" ::
        "perm=cdeflp;modify=20141202223456;type=cdir; /dirA" ::
        "perm=cdeflp;modify=20141202223456;type=dir; /dirA/dir1" ::
        "perm=cdeflp;modify=20141202223456;type=dir; /dirA/dir2" ::
        "perm=adfrw;modify=20141202223456;size=10;type=file; /dirA/digits10.dat" ::
        "perm=adfrw;modify=20141202223456;size=15;type=file; /dirA/digits15.dat" :: Nil
      text.split(EoL) should have size 6
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list specific directory" in {
      client.pasvMode()
      val (n, text) = client.list("MLSD /")
      val expected =
        "perm=cdeflp;modify=20141202223456;type=cdir; /" ::
        "perm=cdeflp;modify=20141202223456;type=dir; /dirA" ::
        "perm=cdeflp;modify=20141202223456;type=dir; /dirB" ::
        "perm=adfrw;modify=20141202223456;size=3;type=file; /abc.txt" ::
        "perm=adfrw;modify=20141202223456;size=6;type=file; /qwerty.txt" :: Nil
      text.split(EoL) should have size 5
      text.split(EoL) should contain theSameElementsAs expected
    }
    "list file with special symbols in it" in {
      client.pasvMode()
      val (n, text) = client.list("""MLSD /dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""")
      val expected =
        """perm=cdeflp;modify=20141202223456;type=pdir; /dirB/dir1/dir2/dir "3" 4""" ::
        """perm=adfrw;modify=20141202223456;size=12;type=file; /dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""" :: Nil
      text.split(EoL) should have size 2
      text.split(EoL) should contain theSameElementsAs expected
    }
  }

  "SIZE command" should {
    "error on empty parameter" in {
      ((client <-- "SIZE") code) should be (501)
    }
    "error on type A" in {
      ((client <-- "SIZE abc.txt") code) should be (550)
    }
    "error on directory" in {
      ((client <-- "SIZE /dirA") code) should be (550)
    }
    "tell file size" in {
      ((client <-- "TYPE I") code) should be (200)
      val reply = client <-- "SIZE /abc.txt"
      reply.code should be (213)
      reply.text should be ("3")
    }
  }

  "MDTM command" should {
    "error on empty parameter" in {
      ((client <-- "MDTM") code) should be (501)
    }
    "error on directory" in {
      ((client <-- "MDTM /dirA") code) should be (550)
    }
    "tell file size" in {
      val reply = client <-- "MDTM /abc.txt"
      reply.code should be (213)
      reply.text should be ("20141202223456")
    }
  }
}

class ArbitraryCommandsSpec extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    client.connect()
    client.login("myuser", "myuser")
  }

  override protected def afterAll() {
    client.disconnect()
    server.stop()
  }

  // switch modes
  "MODE and STRU and TYPE commands" should {
    "error on empty parameter" in {
      ((client <-- "TYPE") code) should be (501)
      ((client <-- "MODE") code) should be (501)
      ((client <-- "STRU") code) should be (501)
    }
    "error on invalid parameter" in {
      ((client <-- "TYPE X") code) should be (504)
      ((client <-- "MODE X") code) should be (504)
      ((client <-- "STRU X") code) should be (504)
    }
    "accept valid parameter" in {
      ((client <-- "TYPE I") code) should be (200)
      ((client <-- "TYPE A") code) should be (200)
      ((client <-- "MODE S") code) should be (200)
      ((client <-- "STRU F") code) should be (200)
    }
  }
}

class CreateDeletePathsSpec extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    createSampleFiles()
    client.connect()
    client.login("myuser", "myuser")
  }

  override protected def afterAll() {
    client.disconnect()
    server.stop()
  }

  "MKD and DELE commands" should {
    "error on empty parameter" in {
      ((client <-- "MKD") code) should be (501)
      ((client <-- "DELE") code) should be (501)
    }
    "create directory" in {
      ((client <-- "MKD /dirN") code) should be (257)
      client.cwd("/dirN")
      ((client <-- "MKD dirX") code) should be (257)
      client.cwd("/dirN/dirX")
    }
    "delete directory" in {
      ((client <-- "DELE /dirN") code) should be (250)
      ((client <-- s"CWD /dirN") code) should be (450)
    }
  }

  "RNFR and RNTO commands" should {
    "error on empty parameter" in {
      ((client <-- "RNFR") code) should be (501)
      ((client <-- "RNTO") code) should be (501)
    }
    "rename file" in {
      ((client <-- "RNFR /abc.txt") code) should be (350)
      ((client <-- "RNTO /abc2.txt") code) should be (250)
    }
    "move file" in {
      ((client <-- "RNFR /abc2.txt") code) should be (350)
      ((client <-- "RNTO /dirA/abc2.txt") code) should be (250)
    }
  }

}

class FileTransferSpec extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    createSampleFiles()
    client.connect()
    client.login("myuser", "myuser")
  }

  override protected def afterAll() {
    client.disconnect()
    server.stop()
  }

  "RETR command" should {
    "receive file with type A" in {
      ((client <-- "TYPE A") code) should be (200)
      val expected = "\r\nline1\r\nline2\r\n\r\nline3\r\n"
      val (n, data) = client <== "/dirA/dir2/multiline-unix.txt"
      n shouldBe expected.size
      data should be (expected.getBytes)
    }
    "receive file with type I" in {
      ((client <-- "TYPE I") code) should be (200)
      val expected = "\nline1\nline2\n\nline3\n"
      val (n, data) = client <== "/dirA/dir2/multiline-unix.txt"
      n shouldBe expected.size
      data should be (expected.getBytes)
    }
  }

  "REST command" should {
    "error on no parameter" in {
      ((client <-- "REST") code) should be (501)
    }
    "error on type A" in {
      ((client <-- "TYPE A") code) should be (200)
      ((client <-- "REST 7") code) should be (550)
    }
    "set marker with type I and receive part of file" in {
      ((client <-- "TYPE I") code) should be (200)
      ((client <-- "REST 7") code) should be (350)
      val expected = "line2\n\nline3\n"
      val (n, data) = client <== "/dirA/dir2/multiline-unix.txt"
      n shouldBe expected.size
      data should be (expected.getBytes)
    }
  }

  "STOR command" should {
    "send file with type A (unix server)" in {
      ((client <-- "TYPE A") code) should be (200)
      val senddata = "\r\nline1\r\nline2\n\nline3\n"
      val expected = "\nline1\nline2\n\nline3\n"
      client <== ("my-multiline.txt", senddata.getBytes)
      server.fileData("/my-multiline.txt") should be (expected.getBytes)
    }
    "send file with type I" in {
      ((client <-- "TYPE I") code) should be (200)
      val expected = "\r\nline1\r\nline2\n\nline3\n"
      client <== ("my-multiline-2.txt", expected.getBytes)
      server.fileData("/my-multiline-2.txt") should be (expected.getBytes)
    }
  }

  "APPE command" should {
    "error on append with type A" in {
      ((client <-- "TYPE A") code) should be (200)
      ((client <-- "APPE /dirA/digits10.dat") code) should be (550)
    }
    "send file with type I" in {
      ((client <-- "TYPE I") code) should be (200)
      val expected = "1234567890-abc\r\n\n"
      client appe ("/dirA/digits10.dat", "-abc\r\n\n".getBytes)
      server.fileData("/dirA/digits10.dat") should be (expected.getBytes)
    }
  }

}

//todo STAT ABOR QUIT with and without active data connection
