package com.coldcore.akkaftp.it
package test

import com.coldcore.akkaftp.it.Utils._
import com.coldcore.akkaftp.it.client.{FtpClient, Reply}
import com.coldcore.akkaftp.it.server.FtpServer
import org.scalatest._

import scala.concurrent.duration._

/** Ensure the FtpClient is working as expected */
class FtpClientSpec extends FlatSpec with BeforeAndAfterAll with BeforeAndAfter with Matchers {

  val server = new FtpServer
  lazy val client = new FtpClient(server.ftpstate)

  override protected def beforeAll() {
    server.start()
  }

  override protected def afterAll() {
    server.stop()
  }

  before {
    client.connect()
  }

  after {
    client.disconnect()
  }

  it should "receive a positive welcome reply" in {
    client.replies should have size 1
    client.replies.head should matchPattern { case Reply(220, _) => }
  }

  it should "send a NOOP coomand and receive a 200 OK reply" in {
    (client <-- "NOOP") should matchPattern { case Reply(200, "OK" | "OK ") => }
    (client <-- "NOOP foo") shouldBe Reply(200, "OK foo")
  }

  it should "STOR a file with PORT" in {
    client.anonymousLogin()
    client.portMode()
    val (n, _) = client <== ("abc.txt", "abc".getBytes)
    n shouldBe 3
    server.fileData("/abc.txt") should be ("abc".getBytes)
  }

  it should "STOR a file with PASV" in {
    client.anonymousLogin()
    client.pasvMode()
    val (n, _) = client <== ("cdef.txt", "cdef".getBytes)
    n shouldBe 4
    server.fileData("/cdef.txt") should be ("cdef".getBytes)
  }

  it should "RETR a file with PORT" in {
    server.addFile("/qwer.txt", "qwer".getBytes)
    client.anonymousLogin()
    client.portMode()
    val (n, data) = client <== "qwer.txt"
    n shouldBe 4
    data should be ("qwer".getBytes)
  }

  it should "RETR a file with PASV" in {
    server.addFile("/qwerty.txt", "qwerty".getBytes)
    client.anonymousLogin()
    client.pasvMode()
    val (n, data) = client <== "qwerty.txt"
    n shouldBe 6
    data should be ("qwerty".getBytes)
  }
}
