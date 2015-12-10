package com.coldcore.akkaftp.it
package test

import org.scalatest._
import com.coldcore.akkaftp.it.server.CustomLauncher
import com.coldcore.akkaftp.it.client.{Reply, FtpClient}
import scala.concurrent.duration._

/** Ensure the FtpClient is working as expected */
class FtpClientSpec extends FlatSpec with BeforeAndAfterAll with BeforeAndAfter with Matchers {

  val launcher = new CustomLauncher
  lazy val client = new FtpClient(launcher.ftpstate)

  def delay(x: FiniteDuration) = Thread.sleep(x.toMillis)

  override protected def beforeAll() {
    launcher.start()
    delay(1 second)
  }

  override protected def afterAll() {
    launcher.stop()
  }

  before {
    client.connect()
    delay(1 second)
  }

  after {
    client.disconnect()
  }

  it should "receive a positive reply" in {
    client.replies should have size 1
    client.replies.head should matchPattern { case Reply(220, _) => }
  }

  it should "send a NOOP coomand and receive a 200 OK reply (with delay)" in {
    client --> "NOOP"
    delay(100 milliseconds)
    client.replies should have size 2
    client.replies.head should matchPattern { case Reply(200, "OK" | "OK ") => }
    client --> "NOOP foo"
    delay(100 milliseconds)
    client.replies should have size 3
    client.replies.head shouldBe Reply(200, "OK foo")
  }

  it should "send a NOOP coomand and receive a 200 OK reply (no delay)" in {
    (client <-> "NOOP") should matchPattern { case Reply(200, "OK" | "OK ") => }
    (client <-> "NOOP foo") shouldBe Reply(200, "OK foo")
  }

}
