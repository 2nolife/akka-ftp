package com.coldcore.akkaftp.it
package client

import com.coldcore.akkaftp.ftp.core.FtpState
import org.scalatest.Matchers

class FtpClient(ftpstate: FtpState) extends Matchers {

  def connect() {
    fail("Not connected")
  }

  def disconnect() {
    fail("Not disconnected")
  }
}