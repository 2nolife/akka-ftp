package com.coldcore.akkaftp.rest
package model

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

case class CtrlExchange(text: String, u2s: Boolean)
object CtrlExchange extends DefaultJsonProtocol {
  implicit val format = jsonFormat2(apply)
}

case class DataExchange(filename: String, u2s: Boolean, success: Boolean)
object DataExchange extends DefaultJsonProtocol {
  implicit val format = jsonFormat3(apply)
}

case class Session(id: Long, connected: String, lastCommand: String, username: String,
                   uploadedBytes: Long, downloadedBytes: Long,
                   chistory: List[CtrlExchange] = List.empty, dhistory: List[DataExchange] = List.empty)
object Session extends DefaultJsonProtocol {
  implicit val format = jsonFormat8(apply)
}

case class Sessions(sessions: Seq[Session])
object Sessions extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(apply)
}

case class SimpleMessage(message: String)
object SimpleMessage extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(apply)
}


