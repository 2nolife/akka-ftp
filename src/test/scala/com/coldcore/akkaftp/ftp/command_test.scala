package com.coldcore.akkaftp.ftp
package command

import com.coldcore.akkaftp.ftp.core.{FtpState, Session}
import org.scalatest.{Matchers, WordSpec}
import org.scalatest.mock.MockitoSugar

class CommandsSpec extends WordSpec with MockitoSugar with Matchers {

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
      val session = new Session(null, new FtpState(null, guest = false, null))
      val reply = UserCommand("anonymous", session).exec
      reply should have ('code (332))
    }
    "accept guest username" in {
      val session = new Session(null, new FtpState(null, guest = true, null))
      val reply = UserCommand("anonymous", session).exec
      reply should have ('code (331))
    }
  }

}
