package com.coldcore.akkaftp.it
package test

import com.coldcore.akkaftp.it.client.FtpClient
import com.coldcore.akkaftp.it.server.FtpServer
import org.scalatest._
import Utils._
import scala.concurrent.duration._

class SimpleScenarioSpec extends WordSpec with BeforeAndAfterAll with Matchers {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
    client.connect()

    implicit def String2Bytes(x: String): Array[Byte] = x.getBytes
    val ad = server.addDirectory _
    val af = server.addFile _

    ad("/dirA")
    ad("/dirA/dir1")
    ad("/dirA/dir2")
    ad("/dirB")
    ad("/dirB/dir1")
    ad("/dirB/dir1/dir2")
    ad("""/dirB/dir1/dir2/dir "3" 4""")

    af("/abc.txt", "abc")
    af("/qwerty.txt", "qwerty")

    af("/dirA/digits10.dat", "1234567890")
    af("/dirA/digits15.dat", "123456789012345")
    af("/dirA/dir1/symbols.12", "qwertyuiop12")
    af("/dirA/dir1/symbols.15", "23-qwertyuiop12")
    af("/dirA/dir1/empty.txt", "")
    af("/dirA/dir2/multiline-unix.txt", "\nline1\nline2\n\nline3\n")
    af("/dirA/dir2/multiline-win.txt", "\r\nlineA\r\nlineB\r\n\r\nlineC\r\n")
    af("/dirA/dir2/multiline-mix.txt", "\r\nlineA\nline2\r\n\r\nline4\n")
    af("/dirA/dir2/multiline-mix-empty.txt", "\r\n\n\r\n\r\n\n")

    af("/dirB/dir1/chunk C", "randomdata-3")
    af("/dirB/dir1/CHUNK C", "randomdata-4")
    af("/dirB/dir1/dir2/chunked random data long name", "randomdata-5")
    af("/dirB/dir1/dir2/c", "randomdata-6")
    af("""/dirB/dir1/dir2/dir "3" 4/chunked "special" 'name'""", "randomdata-7")
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

  //todo LIST NLST, MDTM, SIZE, MLSD, MLST, MKD, DELE, RNFR, RNTO
  //todo RETR, STOR, APPE, REST (with TYPE A/I)

  // logout
  "QUIT command" should {
    "log user out and disconnect" in {
      ((client <-- "QUIT") code) should be (221)
      delay(100 milliseconds)
      client.connected should be (false)
    }
  }

}
