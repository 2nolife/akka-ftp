package com.coldcore.akkaftp.ftp
package userstore

trait UserStore {
  def login(username: String, password: String): Boolean
}

class PropsUserStore(props: Map[String,String]) extends UserStore {
  override def login(username: String, password: String): Boolean =
    props.get(username).orNull == password
}
