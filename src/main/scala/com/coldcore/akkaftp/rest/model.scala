package com.coldcore.akkaftp.rest
package model

import java.util.Date

import spray.json.DefaultJsonProtocol

case class SessionCount(connected: Int, disconnected: Int)
object SessionCount extends DefaultJsonProtocol {
  implicit val format = jsonFormat2(apply)
}

case class Traffic(uploadedBytes: Long, downloadedBytes: Long, uploadByteSec: Long, downloadByteSec: Long)
object Traffic extends DefaultJsonProtocol {
  implicit val format = jsonFormat4(apply)
}

case class Dashboard(sessionCount: SessionCount, traffic: Traffic)
object Dashboard extends DefaultJsonProtocol {
  implicit val format = jsonFormat2(apply)
}

case class Command(in: String, out: String)
object Command extends DefaultJsonProtocol {
  implicit val format = jsonFormat2(apply)
}

case class Session(id: Long, connected: String, lastCommand: String, username: String,
                   uploadedBytes: Long, downloadedBytes: Long)
object Session extends DefaultJsonProtocol {
  implicit val format = jsonFormat6(apply)
}

case class Sessions(sessions: Seq[Session])
object Sessions extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(apply)
}

case class SimpleMessage(message: String)
object SimpleMessage extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(apply)
}


