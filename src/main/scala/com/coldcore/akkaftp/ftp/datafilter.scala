package com.coldcore.akkaftp.ftp
package datafilter

import java.nio.ByteBuffer
import java.nio.channels.{WritableByteChannel, ReadableByteChannel}

import com.coldcore.akkaftp.ftp.session.Session

trait DataFilter {
  /** Test if this data filter may change the length of the data in a stream.
    * Some data filters may modify content and size of the file being transferred
    * but with the REST or APPE commands the output file may be corrupted.
    * TRUE will prevent commands which operate on a file size from executing.
    */
  val modifyDataLength = false

  def readable(rbc: ReadableByteChannel): ReadableByteChannel
  def writable(wbc: WritableByteChannel): WritableByteChannel
}

class TypeADataFilter(endOfLine: String) extends DataFilter {
  override val modifyDataLength = true

  override def writable(wbc: WritableByteChannel): WritableByteChannel =
    new WritableTypeADataFilter(wbc)

  override def readable(rbc: ReadableByteChannel): ReadableByteChannel =
    new ReadableTypeADataFilter(rbc)

  private class ReadableTypeADataFilter(rbc: ReadableByteChannel) extends ReadableByteChannel {
    private var buffer: ByteBuffer = _ // operating on the channel
    private var arrayb: ByteBuffer = _ // data modified by this filter

    override def isOpen: Boolean = rbc.isOpen
    override def close(): Unit = rbc.close()

    override def read(dst: ByteBuffer): Int =
      if (dst.position > 0 || dst.limit != dst.capacity) 0 // do not do anything until the dst buffer is clear, save extra effort
      else {
        val cap = dst.capacity/2 // potential increase of data x2
        if (buffer == null || buffer.capacity > cap) {
          buffer = ByteBuffer.allocate(cap)
          arrayb = ByteBuffer.allocate(cap*2)
        }

        buffer.clear()
        val n = rbc.read(buffer)
        if (n < 1) n
        else {
          val bytes = buffer.array
          val b13: Byte = 13

          arrayb.clear()
          (0 until n).foreach { z => // replace \n with \r\n
            bytes(z) match {
              case 13 =>
              case b if b == 10 => arrayb.put(b13); arrayb.put(b)
              case b => arrayb.put(b)
            }
          }
          arrayb.flip()
          dst.put(arrayb)
          arrayb.position
        }
      }
  }

  private class WritableTypeADataFilter(wbc: WritableByteChannel) extends WritableByteChannel {
    private var buffer: ByteBuffer = _ // operating on the channel
    private var arrayb: ByteBuffer = _ // data modified by this filter

    override def isOpen: Boolean = wbc.isOpen
    override def close(): Unit = wbc.close()

    override def write(src: ByteBuffer): Int = {
      val cap = src.capacity*2 // potential increase of data x2
      if (buffer == null || buffer.capacity > cap) {
        buffer = ByteBuffer.allocate(cap)
        arrayb = ByteBuffer.allocate(cap)
      }

      buffer.clear()
      val n = src.remaining
      buffer.put(src)

      val bytes = buffer.array
      val b13: Byte = 13

      arrayb.clear()
      (0 until n).foreach { z => // replace \n with \r\n or \r\n with \n
        bytes(z) match {
          case 13 =>
          case b if b == 10 && endOfLine == "\r\n" => arrayb.put(b13); arrayb.put(b)
          case b => arrayb.put(b)
        }
      }
      arrayb.flip()

      val r = arrayb.remaining
      val i = wbc.write(arrayb)
      if (i != r) throw new IllegalStateException(s"Corrupted channel write: $i != $r")
      n
    }
  }
}

/**
 * Applies filters to data channels when user uploads or downloads data.
 * Data transfer commands (e.g. RETR, STOR) must use this class to apply required filters
 * before they can proceed to data transfer operations.
 */
class DataFilterApplicator {
  def filters(session: Session): Seq[DataFilter] = {
    val fact = session.ftpstate.dataFilterFactory
    (fact.createStructure(session.dataStructure) ::
     fact.createType(session.dataType) ::
     fact.createMode(session.dataMode) :: Nil).flatten
  }

  def applyFilters(rbc: ReadableByteChannel, session: Session): ReadableByteChannel =
    filters(session).foldLeft(rbc)((x, filter) => filter.readable(rbc))

  def applyFilters(wbc: WritableByteChannel, session: Session): WritableByteChannel =
    filters(session).foldLeft(wbc)((x, filter) => filter.writable(wbc))
}

class DataFilterFactory(endOfLine: String) {
  def createType(x: String): Option[DataFilter] = if (x == "A") Some(new TypeADataFilter(endOfLine)) else None
  def createMode(x: String): Option[DataFilter] = None
  def createStructure(x: String): Option[DataFilter] = None
}