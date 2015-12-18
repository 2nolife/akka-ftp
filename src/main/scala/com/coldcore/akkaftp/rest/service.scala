package com.coldcore.akkaftp.rest
package service

import java.text.SimpleDateFormat

import akka.actor.{ActorLogging, Props, SupervisorStrategy}
import akka.io.IO
import com.coldcore.akkaftp.ftp.core.FtpState
import com.coldcore.akkaftp.rest.core.AdminAuthenticator
import com.coldcore.akkaftp.rest.model._
import com.coldcore.akkaftp.rest.Convert._
import spray.can.Http
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.routing._
import spray.routing.authentication._

import scala.concurrent.duration.DurationInt

object RestService {
  def props(hostname: String, port: Int, ftpstate: FtpState): Props =
    Props(new RestService(hostname, port, ftpstate))
}

class RestService(hostname: String, port: Int, ftpstate: FtpState) extends HttpServiceActor
  with ActorLogging with SprayJsonSupport {
  import context.dispatcher

  IO(Http)(context.system) ! Http.Bind(self, hostname, port)
  log.info(s"Bound Akka FTP REST service to $hostname:$port")

  //todo die when FTP dies context.watch FTP actor

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive =
    runRoute(apiRoute ~ staticRoute)

  def apiRoute: Route =
    authenticate(BasicAuth(new AdminAuthenticator(ftpstate), "Akka FTP")) { user =>
      pathPrefix("api") {
        path("dashboard") {
          get {
            complete {
              val rg = ftpstate.registry
              Dashboard(
                SessionCount(rg.sessions.size, rg.disconnected.size),
                Traffic(rg.uploadedBytes, rg.downloadedBytes, rg.uploadByteSec, rg.downloadByteSec))
            }
          }
        } ~
        pathPrefix("sessions") {
          pathEnd {
            get {
              parameter('disconnected.?) { dsc =>
                complete {
                  val (rg, sdf) = (ftpstate.registry, new SimpleDateFormat("dd/MM/yyyy HH:mm:ss"))
                  val sessions = dsc.map(_ => rg.disconnected).getOrElse(rg.sessions).map(toBriefSession(sdf))
                  Sessions(sessions)
                }
              }
            }
          } ~
          path(Segment) { id =>
            get {
              val safeLong = (s: String) => try { s.toLong } catch { case _: Throwable => -1 }
              val (rg, sdf) = (ftpstate.registry, new SimpleDateFormat("dd/MM/yyyy HH:mm:ss"))
              rg.sessions.find(_.id == safeLong(id)).map { x => // 2 look-ups instead of (sessions ++ disconnected) for performance
                complete(toVerboseSession(sdf)(x))
              } orElse
              rg.disconnected.find(_.id == safeLong(id)).map { x =>
                complete(toVerboseSession(sdf)(x))
              } getOrElse
              complete(StatusCodes.NotFound)
            }
          }
        } ~
        pathPrefix("action") {
          path("suspend") {
            get {
              complete {
                ftpstate.suspended = true
                SimpleMessage("Not accepting new connections")
              }
            }
          } ~
          path("resume") {
            get {
              complete {
                ftpstate.suspended = false
                SimpleMessage("Accepting new connections")
              }
            }
          } ~
          path("stop") {
            get {
              complete {
                SimpleMessage("Stop is not implemented") //todo
              }
            }
          } ~
          path("shutdown") {
            get {
              complete {
                val system = context.system
                system.scheduler.scheduleOnce(1 second)(system.terminate())
                SimpleMessage("Shutting down in 1 second")
              }
            }
          } ~
          path("status") {
            get {
              complete {
                SimpleMessage("Status is not implemented") //todo
              }
            }
          }
        } ~
        path(Segments) { xs =>
          ctx => ctx.complete(StatusCodes.NotFound)
        }
      }
    }

  def staticRoute: Route =
    path("") {
      getFromResource("web/index.html")
    } ~ getFromResourceDirectory("web")

}

