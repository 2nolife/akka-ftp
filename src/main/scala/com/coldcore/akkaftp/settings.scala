package com.coldcore.akkaftp

import akka.actor.{ExtensionKey, Extension, ExtendedActorSystem}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import java.util.Properties

object Settings extends ExtensionKey[Settings]

class Settings(system: ExtendedActorSystem) extends Extension {

  val hostname: String =
    system.settings.config getString "akkaftp.hostname"

  val port: Int =
    system.settings.config getInt "akkaftp.port"

  val timeout: FiniteDuration =
    Duration(system.settings.config.getDuration("akkaftp.timeout", MILLISECONDS), MILLISECONDS)

  val guest: Boolean =
    system.settings.config getBoolean "akkaftp.guest"

  val homedir: String =
    system.settings.config getString "akkaftp.homedir"

  val externalIp: String =
    system.settings.config getString "akkaftp.external_ip"

  val pasvPorts: Seq[Int] =
    (system.settings.config getString "akkaftp.pasv_ports").split("\\,").filter(_.trim != "").map(_.trim.toInt)

  val restHostname: String =
    system.settings.config getString "akkaftp.rest.hostname"

  val restPort: Int =
    system.settings.config getInt "akkaftp.rest.port"
}

object Util {
  def readProperties(filename: String): Map[String,String] = {
    val prop = new Properties
    prop.load(getClass.getResourceAsStream(filename))
    prop.entrySet.asScala.map(e => e.getKey.toString -> e.getValue.toString).toMap
  }
}