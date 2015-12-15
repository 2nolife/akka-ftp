package com.coldcore.akkaftp.it
package test

import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpec}
import com.coldcore.akkaftp.it.server.{FtpServer, CreateSampleFiles}
import com.coldcore.akkaftp.it.client.FtpClient

class BugScenarioSpec extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

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

  "bugged scenario" should {
    "do expected stuff" in {
      // code your flow
    }
  }

}
