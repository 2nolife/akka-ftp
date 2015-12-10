package com.coldcore.akkaftp.it

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import com.coldcore.akkaftp.it.server.CustomLauncher
import com.coldcore.akkaftp.it.client.FtpClient

class DummySpec extends WordSpec with BeforeAndAfterAll with Matchers {

  val launcher = new CustomLauncher()
  val client = new FtpClient(launcher.ftpstate)

  override protected def beforeAll() {
    launcher.start()
  }

  override protected def afterAll() {
    launcher.stop()
  }

  "this" should {
    "not fail" in {
      assertResult(true) { false }
    }
  }

  "this" should {
    "connect to server" in {
      client.connect()
    }
  }

}
