package com.coldcore.akkaftp.ftp
package command

import com.coldcore.akkaftp.ftp.core.{FtpState, Session}
import org.scalatest.{Matchers, WordSpec}
import org.scalatest.mock.MockitoSugar

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

}
