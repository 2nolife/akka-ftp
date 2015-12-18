package com.coldcore.akkaftp.rest

import com.coldcore.akkaftp.ftp.session.{Session => FSession, CtrlExchange => FCtrlExchange, DataExchange => FDataExchange, DataExchangeEnd => FDataExchangeEnd, DataExchangeStart => FDataExchangeStart}
import java.text.SimpleDateFormat
import com.coldcore.akkaftp.rest.model.{DataExchange, CtrlExchange, Session}
import java.util.Date

object Convert {

  def toBriefSession(sdf: SimpleDateFormat)(x: FSession): Session =
    Session(
      x.id,
      x.attributes.get[Date]("connected.date").map(sdf.format).orNull,
      x.attributes.get[Date]("lastCommand.date").map(sdf.format).orNull,
      x.username.orNull,
      x.uploadedBytes,
      x.downloadedBytes)

  def toVerboseSession(sdf: SimpleDateFormat)(x: FSession): Session = {
    val cs =
      x.attributes.get[List[FCtrlExchange]](x.chistoryKey).map { as =>
        for (a <- as) yield CtrlExchange(a.text, a.userToServer)
      }.getOrElse(List.empty[CtrlExchange])
    val ds =
      x.attributes.get[List[FDataExchange]](x.dhistoryKey).map { as =>
        as.flatMap {
          case d: FDataExchangeStart => None
          case d: FDataExchangeEnd => Some(DataExchange(d.filename, d.userToServer, d.success))
        }
      }.getOrElse(List.empty[DataExchange])

    Session(
      x.id,
      x.attributes.get[Date]("connected.date").map(sdf.format).orNull,
      x.attributes.get[Date]("lastCommand.date").map(sdf.format).orNull,
      x.username.orNull,
      x.uploadedBytes,
      x.downloadedBytes,
      cs, ds)
  }
}
