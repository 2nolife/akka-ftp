package com.coldcore.akkaftp

import java.nio.channels.Channel

package object ftp {

  implicit class ChannelX(x: Channel) {
    def safeClose() = try { x.close() } catch { case _: Throwable => }
  }

}