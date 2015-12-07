package com.coldcore.akkaftp.rest
package core

import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.rest.service.RestService
import spray.routing.authentication._

import scala.concurrent.{Promise, Future}

object Boot {
  def apply(hostname: String, port: Int, ftpstate: FtpState) =
    new Boot(hostname, port, ftpstate)
}

class Boot(hostname: String, port: Int, ftpstate: FtpState) {
  val host = if (hostname == "") "0.0.0.0" else hostname
  ftpstate.system.actorOf(RestService.props(host, port, ftpstate), name = "rest-service")
}

class AdminAuthenticator(ftpstate: FtpState) extends UserPassAuthenticator[BasicUserContext] {
  override def apply(userPass: Option[UserPass]): Future[Option[BasicUserContext]] = {
    val basicUserContext =
      userPass flatMap {
        case UserPass(username @ "admin", password) if ftpstate.userStore.login(username, password) =>
          Some(BasicUserContext(username))
        case _ => None
      }
    Promise.successful(basicUserContext).future
  }
}
