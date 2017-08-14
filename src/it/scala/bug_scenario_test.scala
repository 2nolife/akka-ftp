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

/*
import java.io.{File, PrintWriter}
import com.coldcore.akkaftp.it.server.{CreateSampleFiles, FtpServer}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class UploadWithCurl extends WordSpec with BeforeAndAfterAll with Matchers with CreateSampleFiles {

  val server = new FtpServer

  "curl scenario" should {

    server.start()

    createSampleFiles()

    def tryUpload(): Int = {
      val f = File.createTempFile("temp-file-name", ".tmp")
      f.deleteOnExit()
      f.createNewFile()
      val path = f.getAbsolutePath

      val writer = new PrintWriter(f)
      writer.write("hello")
      writer.close()

      import sys.process._
      s"curl -v -T $path ftp://127.0.0.1:2021 --user myuser:myuser" !
    }

    (0 to 10).map(_ -> tryUpload()) should be( (0 to 10).map(_ -> 0))
  }
}
*/
