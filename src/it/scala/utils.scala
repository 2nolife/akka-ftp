package com.coldcore.akkaftp.it

import scala.concurrent.duration.FiniteDuration

object Utils {
  def delay(x: FiniteDuration) = Thread.sleep(x.toMillis)
}
