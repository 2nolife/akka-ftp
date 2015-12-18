package com.coldcore.akkaftp.ftp
package filesystem

import java.io.{RandomAccessFile, File => jFile}
import java.nio.channels.{ReadableByteChannel, WritableByteChannel}
import java.util.Date
import java.util.regex.Pattern

import com.coldcore.akkaftp.ftp.session.Session

trait FileSystem {
  val separator: String = "/"
  val endOfLine: String = "\n"
  def file(path: String, session: Session): File
  def login(session: Session)
  def logout(session: Session)
}

trait File {
  val path: String
  def listFile: Option[ListingFile]
  def listFiles: Seq[ListingFile]
  def exists: Boolean
  def read(position: Long): ReadableByteChannel
  def write(append: Boolean): WritableByteChannel
  def delete()
  def mkdir()
  def rename(dst: File)
  def parent: Option[File]
}

class ListingFile(val owner: String, val directory: Boolean, val permissions: String, val mlsxFacts: String,
                  val size: Long, val name: String, val path: String, val modified: Date)

sealed trait FailedReason
case object NoPermissionsFR extends FailedReason
case object PathErrorFR extends FailedReason
case object InvalidInputFR extends FailedReason
case object SystemErrorFR extends FailedReason
case object NotImplementedFR extends FailedReason
case object OtherFR extends FailedReason

class FileSystemException(val reason: FailedReason, m: String, t: Throwable) extends RuntimeException(m, t) {
  def this(reason: FailedReason, m: String) = this(reason, m, null)
}

class DiskFileSystem(root: String) extends FileSystem {
  override val endOfLine: String = if (jFile.separator == "\\") "\r\n" else "\n"

  override def file(path: String, session: Session) = {
    val homeDir = session.homeDir.asInstanceOf[JFile].target.getAbsolutePath
    new JFile(target = new jFile(homeDir, path), session)
  }

  override def login(session: Session) {
    val target = new jFile(root, session.username.get)
    val homeDir = new JFile(target, session, Some("/")) // user home dir is "/"
    target.mkdirs()
    session.homeDir = homeDir
    session.currentDir = homeDir
  }

  override def logout(session: Session) {}
}

class JFile(val target: jFile, session: Session, val vpath: Option[String] = None) extends File {

  override val path: String = vpath.getOrElse {
    val p = target.getAbsolutePath.replaceAll(Pattern.quote("\\"), "/")
    val h = session.homeDir.asInstanceOf[JFile].target.getAbsolutePath.replaceAll(Pattern.quote("\\"), "/")
    if (!(p+"/").startsWith(h+"/")) throw new FileSystemException(NoPermissionsFR, "Path is outside of user home directory")

    p.substring(h.length) match {
      case "" => "/"
      case x if x.head != '/' => "/"+x
      case x => x
    }
  }

  override def exists: Boolean = target.exists

  override def listFile: Option[ListingFile] =
    if (!target.exists) None
    else Some(new ListingFile("ftp", target.isDirectory, "rwxrwxrwx", if (target.isDirectory) "cdeflp" else "adfrw",
              target.length, target.getName, path, new Date(target.lastModified)))

  override def listFiles: Seq[ListingFile] =
    if (!target.exists) Seq.empty[ListingFile]
    else if (target.isFile) Seq(listFile.get)
    else target.listFiles.flatMap(new JFile(_, session).listFile)

  override def read(position: Long): ReadableByteChannel = {
    if (path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid file name.")
    if (!target.exists) throw new FileSystemException(PathErrorFR, "File not found.")
    if (target.isDirectory) throw new FileSystemException(PathErrorFR, "Path is a directory.")

    try { new RandomAccessFile(target, "r").getChannel.position(position) }
    catch { case e: Throwable => throw new FileSystemException(SystemErrorFR, "Cannot create (r) file channel", e) }
  }

  override def write(append: Boolean): WritableByteChannel = {
    if (path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid file name.")
    if (!target.getParentFile.exists) throw new FileSystemException(PathErrorFR, "Directory not found.")
    if (target.exists && target.isDirectory) throw new FileSystemException(PathErrorFR, "Path already exists.")

    try {
      val fc = new RandomAccessFile(target, "rw").getChannel
      if (append) fc.position(fc.size) else fc.truncate(0)
    } catch { case e: Throwable => throw new FileSystemException(SystemErrorFR, "Cannot create (rw) file channel", e) }
  }

  private def delete(file: jFile): Unit =
    if (file.isFile) file.delete()
    else {
      file.listFiles.filter(_.isFile).foreach(_.delete()) // delete all files
      (file.listFiles :+ file).foreach(delete) // delete all files including this dir
    }

  override def delete() {
    if (path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid path.")
    if (!target.exists) throw new FileSystemException(PathErrorFR, "Path not found.")

    delete(target)
  }

  override def mkdir() {
    if (path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid directory name.")
    if (target.exists) throw new FileSystemException(PathErrorFR, "Path already exists.")
    if (!target.getParentFile.exists) throw new FileSystemException(PathErrorFR, "Directory not found.")

    target.mkdirs()
  }

  override def rename(dst: File) {
    if (path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid path.")
    if (!target.exists) throw new FileSystemException(PathErrorFR, "Path not found.")

    val that = dst.asInstanceOf[JFile].target
    if (dst.path.endsWith("/")) throw new FileSystemException(InvalidInputFR, "Invalid path.")
    if (that.exists) throw new FileSystemException(PathErrorFR, "Path already exists.")
    if (!that.getParentFile.exists) throw new FileSystemException(PathErrorFR, "Directory not found.")

    target.renameTo(that)
  }

  override def parent: Option[File] =
    if (path != "/") Some(new JFile(target.getParentFile, session)) else None
}
