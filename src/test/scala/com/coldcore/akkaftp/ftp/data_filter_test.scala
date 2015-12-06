package com.coldcore.akkaftp.ftp
package datafilter

import org.scalatest._
import org.scalatest.mock.MockitoSugar
import java.nio.channels.Channels
import java.nio.ByteBuffer
import java.io.{PipedOutputStream, PipedInputStream, ByteArrayInputStream, ByteArrayOutputStream}

class TypeADataFilterSpec extends FlatSpecLike with MockitoSugar with Matchers with BeforeAndAfter {

  val buffer = ByteBuffer.allocate(100)

  before {
    buffer.clear()
  }

  it should "filter \\r\\n to \\r\\n on upload" in { // windows client --> windows server
    val filter = new TypeADataFilter("\r\n")
    val bout = new ByteArrayOutputStream()
    val wbc = filter.writable(Channels.newChannel(bout))

    val data = "123\r\n456\r\n789"
    val expected = data

    buffer.put(data.getBytes)
    buffer.flip()
    val n = wbc.write(buffer)

    new String(bout.toByteArray) should equal (expected)
    data should have length n
  }

  it should "filter \\r\\n to \\n on upload" in { // windows client --> unix server
    val filter = new TypeADataFilter("\n")
    val bout = new ByteArrayOutputStream()
    val wbc = filter.writable(Channels.newChannel(bout))

    val data = "123\r\n456\r\n789"
    val expected = "123\n456\n789"

    buffer.put(data.getBytes)
    buffer.flip()
    val n = wbc.write(buffer)

    new String(bout.toByteArray) should equal (expected)
    data should have length n
  }

  it should "filter \\n to \\r\\n on upload" in { // unix client --> windows server
    val filter = new TypeADataFilter("\r\n")
    val bout = new ByteArrayOutputStream()
    val wbc = filter.writable(Channels.newChannel(bout))

    val data = "123\n456\n789"
    val expected = "123\r\n456\r\n789"

    buffer.put(data.getBytes)
    buffer.flip()
    val n = wbc.write(buffer)

    new String(bout.toByteArray) should equal (expected)
    data should have length n
  }

  it should "filter \\r\\n to \\n on upload x2" in { // windows client --> unix server
    val filter = new TypeADataFilter("\n")
    val bout = new ByteArrayOutputStream()
    val wbc = filter.writable(Channels.newChannel(bout))

    val data = "123\r\n456\r\n789"
    val expected = "123\n456\n789"

    buffer.put(data.getBytes)
    buffer.flip()
    val n = wbc.write(buffer)

    new String(bout.toByteArray) should equal (expected)
    data should have length n

    val data2 = "000\r\n111\r\n"
    val expected2 = "000\n111\n"

    buffer.clear()
    buffer.put(data2.getBytes)
    buffer.flip()
    val n2 = wbc.write(buffer)

    new String(bout.toByteArray) should equal (expected+expected2)
    data2 should have length n2
  }

  it should "filter \\r\\n to \\r\\n on download" in { // any client <-- windows server
    val data = "123\r\n456\r\n789"
    val expected = data

    val filter = new TypeADataFilter("\r\n")
    val bin = new ByteArrayInputStream(data.getBytes)
    val rbc = filter.readable(Channels.newChannel(bin))

    val n = rbc.read(buffer)
    val bytes = buffer.array.take(n)

    new String(bytes) should equal (expected)
    data should have length n
  }

  it should "filter \\n to \\r\\n on download" in { // any client <-- unix server
    val data = "123\n456\n789"
    val expected = "123\r\n456\r\n789"

    val filter = new TypeADataFilter("\n")
    val bin = new ByteArrayInputStream(data.getBytes)
    val rbc = filter.readable(Channels.newChannel(bin))

    val n = rbc.read(buffer)
    val bytes = buffer.array.take(n)

    new String(bytes) should equal (expected)
    expected should have length n
  }

  it should "filter \\n to \\r\\n on download x2" in { // any client <-- unix server
    val filter = new TypeADataFilter("\n")
    val pipeOut = new PipedOutputStream()
    val pipeIn = new PipedInputStream(pipeOut)
    val rbc = filter.readable(Channels.newChannel(pipeIn))

    val data = "123\n456\n789"
    val expected = "123\r\n456\r\n789"

    pipeOut.write(data.getBytes)
    val n = rbc.read(buffer)
    val bytes = buffer.array.take(n)

    new String(bytes) should equal (expected)
    expected should have length n

    val data2 = "000\n111\n"
    val expected2 = "000\r\n111\r\n"

    pipeOut.write(data2.getBytes)
    buffer.clear()
    val n2 = rbc.read(buffer)
    val bytes2 = buffer.array.take(n2)

    new String(bytes2) should equal (expected2)
    expected2 should have length n2
  }

}
