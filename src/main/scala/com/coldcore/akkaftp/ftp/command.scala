package com.coldcore.akkaftp.ftp
package command

import java.util.regex.Pattern

import core.Constants._
import com.coldcore.akkaftp.ftp.core._
import com.coldcore.akkaftp.ftp.filesystem._
import java.util.{TimeZone, Locale}
import java.text.SimpleDateFormat
import java.io.ByteArrayInputStream
import java.nio.channels.{WritableByteChannel, ReadableByteChannel, Channels}
import com.coldcore.akkaftp.ftp.connection.{ControlConnection, DataConnection, DataConnectionInitiator}
import java.net.InetSocketAddress

case class Reply(code: Int, text: String = "", noop: Boolean = false, next: Option[Reply] = None) {
  def serialize: String =
    text match {
      case "" => code+EoL  // just code
      case x if !x.contains(EoL) => code+" "+x+EoL // code and one line text
      case x => // code and multi line text
        val lines = x.split("\n").map(_.trim+EoL)
        code.toString+"-" + lines.dropRight(1).map(" "+).mkString + code+" "+lines.last
    }
}

object CommonReplies {
  val notLoggedIn = Reply(530, "Not logged in.")
  val localError = Reply(451, s"Requested action aborted. Local error in processing.")
  val noop = Reply(0, "", noop = true)
}

trait Command {
  val session: Session
  def exec: Reply
}

/** Commands with this trait execute only after user successful login */
trait LoggedIn {
  self: Command =>
}

/** Commands with this trait can execute when a connection is in the "interrupt" state */
trait Interrupt {
  self: Command =>
  val replyClearsInterrupt = false // if a reply to the command must clear the "interrupt" state of a connection
}

trait DataTransferOps {
  self: Command =>
  val session: Session

  def openerNotSet: Option[Reply] =
    if (session.dataOpenerType.isDefined) None
    else Some(Reply(425, "Can't open data connection."))


  def openerStart() {
    session.dataOpenerType match {
      case Some(PasvDOT) => // PASV parameters active just once per data transfer
        session.dataOpenerType = None
        //todo
      case Some(PortDOT) => // PORT activates data connection initiator
        val (system, ep) = (session.ftpstate.system, session.dataEndpoint.get)
        system.actorOf(DataConnectionInitiator.props(ep, session), name = "data-initiator-"+ID.next)
      case None => throw new IllegalStateException(s"No data opener type set")
    }
  }

  def closeChannel() {
    session.dataTransferChannel.foreach(_.safeClose())
    session.dataTransferChannel = None
  }
}

trait FileSystemOps {
  self: Command =>
  val session: Session

  def handleFsError(e: FileSystemException): Reply = {
    val m = e.getMessage
    e.reason match {
      case NoPermissionsFR => Reply(550, s"No permission. $m")
      case InvalidInputFR => Reply(553, s"Cannot parse input. $m")
      case PathErrorFR => Reply(450, s"Requested path error. $m")
      case SystemErrorFR => Reply(451, s"Requested action aborted. Local error in processing. $m")
      case NotImplementedFR => Reply(504, s"Not implemented. $m")
      case OtherFR => Reply(450, s"Unknown reason. $m")
    }
  }

  def filenamePath(filename: String): String =
    if (filename.startsWith("/")) filename else (session.currentDir.path+"/"+filename).replace("//", "/")

  def readableFileChannel(filename: String): Either[ReadableByteChannel,Reply] = {
    val path = filenamePath(filename)
    val x: Either[ReadableByteChannel,Reply] =
      try { Left(session.ftpstate.fileSystem.file(path, session).read(session.dataMarker)) }
      catch { case e: FileSystemException => Right(handleFsError(e)) }
    x.left.map(rbc => session.ftpstate.dataFilterApplicator.applyFilters(rbc, session))
  }

  def writableFileChannel(filename: String, append: Boolean = false): Either[WritableByteChannel,Reply] = {
    val path = filenamePath(filename)
    val x: Either[WritableByteChannel,Reply] =
      try { Left(session.ftpstate.fileSystem.file(path, session).write(append)) }
      catch { case e: FileSystemException => Right(handleFsError(e)) }
    x.left.map(wbc => session.ftpstate.dataFilterApplicator.applyFilters(wbc, session))
  }
}

trait CommandFactory {
  def create(text: String, session: Session): Command
  def names: Seq[String]
}

class DefaultCommandFactory extends CommandFactory {
  def defcmd(name: String, param: String, session: Session): Option[Command] =
    Option(name match {
      case "USER" => UserCommand(param, session)
      case "PASS" => PassCommand(param, session)
      case "PWD" => PwdCommand(session)
      case "TYPE" => TypeCommand(param, session)
      case "MODE" => ModeCommand(param, session)
      case "STRU" => StruCommand(param, session)
      case "NOOP" => NoopCommand(param, session)
      case "SYST" => SystCommand(session)
      case "ALLO" => AlloCommand(session)
      case "PORT" => PortCommand(param, session)
      case "LIST" => ListCommand(param, session)
      case "NLST" => NlstCommand(param, session)
      case "CWD" => CwdCommand(param, session)
      case "RETR" => RetrCommand(param, session)
      case "STOR" => StorCommand(param, session)
      case "APPE" => AppeCommand(param, session)
      case "STOU" => StouCommand(session)
      case "REST" => RestCommand(param, session)
      case "CDUP" => CdupCommand(session)
      case "DELE" => DeleCommand(param, session)
      case "MKD" => MkdCommand(param, session)
      case "RNFR" => RnfrCommand(param, session)
      case "RNTO" => RntoCommand(param, session)
      case "STAT" => StatCommand(param, session)
      case "ABOR" => AborCommand(session)
      case "QUIT" => QuitCommand(session)

      case "EPRT" => EprtCommand(param, session)

      case "TVFS" => TvfsCommand(session)
      case "MDTM" => MdtmCommand(param, session)
      case _ => null
    })

  /** Overwrite this method to add your specific commands
    * eg. def cmd = mycmd erElse defcmd orElse unkcmd
    */
  def cmd(name: String, param: String, session: Session) =
    defcmd(name, param, session) getOrElse UnknownCommand(session)

  override def create(text: String, session: Session): Command =
    cmd(text.takeWhile(' '!=), text.dropWhile(' '!=).trim, session)

  override def names: Seq[String] =
    "USER" :: "PASS" :: "PWD" :: "TYPE" :: "MODE" :: "STRU" :: "NOOP" :: "SYST" ::
    "ALLO" :: "PORT" :: "LIST" :: "NLST" :: "CWD" :: "RETR" :: "STOR" :: "APPE" ::
    "STOU" :: "REST" :: "CDUP" :: "DELE" :: "MKD" :: "RNFR" :: "RNTO" :: "STAT" ::
    "ABOR" :: "QUIT" ::
    "EPRT" ::
    "TVFS" :: "MDTM" :: Nil
}

/*** Special commands ***/

case class WelcomeCommand(session: Session) extends Command {
  override def exec: Reply = Reply(220, "Welcome to Akka FTP - the open source FTP server")
}

case class UnknownCommand(session: Session) extends Command {
  override def exec: Reply = Reply(504, "Not implemented.")
}

case class TransferSuccessCommand(session: Session) extends Command with Interrupt {
  override val replyClearsInterrupt = true
  override def exec: Reply =
    session.dataTransferMode.get match {
      case StouDTM =>
        Reply(250, "Transfer completed.")
      case ListDTM =>
        Reply(226, "Transfer completed.")
      case _ =>
        val encf = session.dataFilename.get.replaceAll("\"", "\"\"") // encode double-quoted in the filename
        Reply(226, s"""Transfer completed for "$encf".""")

    }
}

case class TransferFailedCommand(session: Session) extends Command with Interrupt {
  override val replyClearsInterrupt = true
  override def exec: Reply = Reply(426, "Connection closed, transfer aborted.")
}

case class TransferAbortedCommand(session: Session) extends Command with Interrupt {
  override val replyClearsInterrupt = true
  override def exec: Reply =
    Reply(426, "Connection closed, transfer aborted.",
          next = Some(Reply(226, "Abort command successful.")))
}

case class UnavailableCommand(session: Session) extends Command {
  override def exec: Reply = Reply(421, "Service not available, closing control connection.")
}

/*** Regular commands ***/

case class UserCommand(param: String, session: Session) extends Command {
  override def exec: Reply =
    param match {
      case _ if session.loggedIn =>
        Reply(503, "Already logged in.")
      case "" =>
        Reply(501, "Send your user name.")
      case username @ "anonymous" if session.ftpstate.guest =>
        session.username = Some(username)
        Reply(331, "Guest login okay, send your complete e-mail address as password.")
      case "anonymous" =>
        Reply(332, "Anonymous login disabled, need account for login.")
      case username =>
        session.username = Some(username)
        Reply(331, "User name okay, need password.")
    }
}

case class PassCommand(param: String, session: Session) extends Command {
  val emailRegEx = "([a-zA-Z0-9_\\-\\.]+)@([a-zA-Z0-9_\\-\\.]*)"

  override def exec: Reply =
    param match {
      case _ if session.loggedIn =>
        Reply(503, "Already logged in.")
      case _ if session.username.isEmpty =>
        Reply(503, "Send your user name.")
      case "" =>
        Reply(501, "Send your password.")
      case password if session.guest =>
        if (password.matches(emailRegEx)) {
          session.password = Some(password)
          session.login()
          Reply(230, "User logged in, proceed.")
        } else Reply(530, "Not logged in.")
      case password if session.ftpstate.userStore.login(session.username.get, password) =>
        session.password = Some(password)
        session.login()
        Reply(230, "User logged in, proceed.")
      case _ =>
        CommonReplies.notLoggedIn
    }
}

case class PwdCommand(session: Session) extends Command with LoggedIn {
  override def exec: Reply = {
    val dir = session.currentDir.path.replaceAll("\"", "\"\""); //encode double-quoted
    Reply(257, s""" "$dir" is current directory.""".trim)
  }
}

case class ModeCommand(param: String, session: Session) extends Command with LoggedIn {
  override def exec: Reply =
    param match {
      case m @ "S" =>
        session.dataMode = m
        Reply(200, "Command okay.")
      case "" =>
        Reply(501, "Syntax error in parameters or arguments.")
      case _ =>
        Reply(504, "Supported only S.")
    }
}

case class StruCommand(param: String, session: Session) extends Command with LoggedIn {
  override def exec: Reply =
    param match {
      case s @ "F" =>
        session.dataStructure = s
        Reply(200, "Command okay.")
      case "" =>
        Reply(501, "Syntax error in parameters or arguments.")
      case _ =>
        Reply(504, "Supported only F.")
    }
}

case class TypeCommand(param: String, session: Session) extends Command with LoggedIn {
  override def exec: Reply =
    param match {
      case t @ ("A" | "I") =>
        session.dataType = t
        Reply(200, "Type set to "+t)
      case "" =>
        Reply(501, "Syntax error in parameters or arguments.")
      case _ =>
        Reply(504, "Supported only A, I.")
    }
}

case class NoopCommand(param: String, session: Session) extends Command {
  override def exec: Reply = Reply(200, s"OK $param".trim)
}

case class AlloCommand(session: Session) extends Command {
  override def exec: Reply = Reply(202, "Command not implemented, superfluous at this site.")
}

case class SystCommand(session: Session) extends Command {
  override def exec: Reply = {
    val t = if (session.ftpstate.fileSystem.separator == "\\") "Windows" else "Unix"
    Reply(215, t+" system type.")
  }
}

case class PortCommand(param: String, session: Session) extends Command with LoggedIn {
  override def exec: Reply = {
    val safeInt = (s: String) => try { s.toInt } catch { case _: Throwable => -1 }
    session.dataOpenerType = None

    val hostport = // host and port client listens to
      param.split(",") match {
        case arr @ Array(_, _, _, _, p1, p2) if arr.forall(safeInt(_) > -1) =>
          val host = arr.take(4).mkString(".")
          val port = safeInt(p1)*256+safeInt(p2)
          Some((host, port))
        case _ => None
      }
    hostport.map { case (host, port) =>
      session.dataOpenerType = Some(PortDOT)
      session.dataEndpoint = Some(new InetSocketAddress(host, port))
      Reply(200, "PORT command successful.")
    }.getOrElse {
      Reply(501, "Send correct IP and port number.")
    }
  }
}

abstract class ListNlstCommand(param: String, val session: Session) extends Command with LoggedIn with DataTransferOps with FileSystemOps {
  override def exec: Reply = {
    val either: Either[File,Reply] =
      try { Left {
          if (param.nonEmpty && !param.contains("*") && !param.startsWith("-")) session.ftpstate.fileSystem.file(param, session)
          else session.currentDir
      }} catch { case e: FileSystemException => Right(handleFsError(e)) }

    either match {
      case Left(listdir) =>
        val str = serializeList(listdir.listFiles)
        val rbc = Channels.newChannel(new ByteArrayInputStream(str.getBytes(UTF8)))
        session.dataTransferMode = Some(ListDTM)
        session.dataTransferChannel = Some(rbc)
        openerNotSet.getOrElse {
          openerStart()
          Reply(150, s"Opening A mode data connection for ${listdir.path}.")
        }
      case Right(reply) =>
        reply
    }
  }

  def serializeList(list: Seq[ListingFile]): String
}

/** Construct a string for List and Stat commands */
trait SerializeListing {
  def serialize(list: Seq[ListingFile]): String = {
    val sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH)
    list.map { f =>
    //-rw------- 1 peter 848 Dec 14 11:22 README.txt\r\n
      val bit = if (f.directory) "d" else "-"
      val mod = sdf.format(f.modified)
      s"$bit ${f.permissions} 1 ${f.owner} ${f.size} $mod ${f.name}$EoL"
    }.mkString
  }
}

case class ListCommand(param: String, override val session: Session) extends ListNlstCommand(param, session) with SerializeListing {
  override def serializeList(list: Seq[ListingFile]): String = serialize(list)
}

case class NlstCommand(param: String, override val session: Session) extends ListNlstCommand(param, session) {
  override def serializeList(list: Seq[ListingFile]): String = {
    val fileSeparator = session.ftpstate.fileSystem.separator
    list.map { f =>
      val bit = if (f.directory) fileSeparator else ""
      s"${f.name}$bit$EoL"
    }.mkString
  }
}

case class CwdCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(250, "CWD command successful.")
      case path =>
        try {
          session.currentDir = session.ftpstate.fileSystem.file(path, session)
          Reply(250, "Directory changed.")
        } catch { case e: FileSystemException => handleFsError(e) }
    }
}

case class RetrCommand(param: String, session: Session) extends Command with LoggedIn with DataTransferOps with FileSystemOps {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send file name.")
      case filename =>
        closeChannel()
        readableFileChannel(filename) match {
          case Left(rbc) =>
            openerNotSet.map { reply =>
              rbc.safeClose()
              reply
            } getOrElse {
              session.dataFilename = Some(filename)
              session.dataTransferMode = Some(RetrDTM)
              session.dataTransferChannel = Some(rbc)
              openerStart()
              Reply(150, s"Opening ${session.dataType} mode data connection for $filename.")
            }
          case Right(reply) =>
            reply
        }
    }
}

abstract class StorAppeStouCommand(session: Session) extends Command with LoggedIn with DataTransferOps with FileSystemOps {
  def execute(mode: DataTransferMode, filename: String, append: Boolean = false): Reply = {
    closeChannel()
    writableFileChannel(filename, append) match {
      case Left(wbc) =>
        openerNotSet.map { reply =>
          wbc.safeClose()
          reply
        } getOrElse {
          session.dataFilename = Some(filename)
          session.dataTransferMode = Some(StorDTM)
          session.dataTransferChannel = Some(wbc)
          openerStart()
          Reply(150, s"Opening ${session.dataType} mode data connection for $filename.")
        }
      case Right(reply) =>
        reply
    }
  }
}

case class StorCommand(param: String, session: Session) extends StorAppeStouCommand(session) {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send file name.")
      case filename =>
        execute(StorDTM, filename)
    }
}

case class AppeCommand(param: String, session: Session) extends StorAppeStouCommand(session) {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send file name.")
      case _ if session.ftpstate.dataFilterApplicator.filters(session).exists(_.modifyDataLength) =>
        Reply(550, s"APPE unavailable for TYPE ${session.dataType}, MODE ${session.dataMode}, STRU ${session.dataStructure}.")
      case filename =>
        execute(StorDTM, filename, append = true)
    }
}

case class StouCommand(session: Session) extends StorAppeStouCommand(session) {
  override def exec: Reply =
    session.currentDir.parent.map(x => execute(StouDTM, x.path)).getOrElse {
      Reply(550, "STOU unavailable for current directory.")
    }
}

case class RestCommand(param: String, session: Session) extends Command with LoggedIn {
  val safeLong = (s: String) => try { s.toLong } catch { case _: Throwable => -1 }
  override def exec: Reply =
    param match {
      case marker if safeLong(marker) < 0 =>
        Reply(501, "Send correct marker.")
      case _ if session.ftpstate.dataFilterApplicator.filters(session).exists(_.modifyDataLength) =>
        Reply(550, s"REST unavailable for TYPE ${session.dataType}, MODE ${session.dataMode}, STRU ${session.dataStructure}.")
      case marker =>
        session.dataMarker = safeLong(marker)
        Reply(350, "Marker set.")
    }
}

case class CdupCommand(session: Session) extends Command with LoggedIn {
  override def exec: Reply = {
    session.currentDir.parent.map(session.currentDir = _)
    Reply(250, "Directory changed.")
  }
}

case class DeleCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send path name.")
      case path =>
        try {
          session.ftpstate.fileSystem.file(path, session).delete()
          Reply(250, "Path deleted.")
        } catch { case e: FileSystemException => handleFsError(e) }
    }
}

case class MkdCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send directory name.")
      case dir =>
        try {
          session.ftpstate.fileSystem.file(dir, session).mkdir()
          val enc = dir.replaceAll("\"", "\"\"")
          Reply(257, s""" "$enc" directory created.""".trim)
        } catch { case e: FileSystemException => handleFsError(e) }
    }
}

case class RnfrCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply = {
    val key = "RnfrCommand.renameFrom"
    session.attributes.rem(key)
    param match {
      case "" =>
        Reply(501, "Send path name.")
      case path =>
        try {
          val rnfr = session.ftpstate.fileSystem.file(path, session)
          session.attributes.set(key, rnfr)
          Reply(350, "Send RNTO to complete rename.")
        } catch {
          case e: FileSystemException => handleFsError(e)
        }
    }
  }
}

case class RntoCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply = {
    val rnfr = session.attributes.get[File]("RnfrCommand.renameFrom")
    param match {
      case "" =>
        Reply(501, "Send path name.")
      case _ if rnfr.isEmpty =>
        Reply(503, "Send RNFR first.")
      case path =>
        try {
          val rnto = session.ftpstate.fileSystem.file(path, session)
          rnfr.get.rename(rnto)
          Reply(250, "Path renamed.")
        } catch {
          case e: FileSystemException => handleFsError(e)
        }
    }
  }
}

case class StatCommand(param: String, session: Session) extends Command with LoggedIn with SerializeListing with FileSystemOps with Interrupt {
  override def exec: Reply = {
    if (session.dataConnection.isDefined) Reply(221, "Waiting for data transfer to finish.")
    else param match {
      case "" =>
        Reply(211, s"Control connection OK, TYPE ${session.dataType}, MODE ${session.dataMode}, STRU ${session.dataStructure}.")
      case path =>
        try {
          val listdir = session.ftpstate.fileSystem.file(path, session)
          val str = serialize(listdir.listFiles)+"end"
          Reply(212, "List results:\r\n"+str)
        } catch { case e: FileSystemException => handleFsError(e) }
    }
  }
}

case class AborCommand(session: Session) extends Command with LoggedIn with Interrupt {
  override val replyClearsInterrupt = true
  override def exec: Reply = {
    //todo abort PASV listener for the connection

    session.dataConnection.map { conn =>
      conn ! DataConnection.Abort
      CommonReplies.noop // connection will send proper replies
    }.getOrElse {
      Reply(226, "Abort command successful.")
    }
  }
}

case class QuitCommand(session: Session) extends Command with LoggedIn with Interrupt {
  override def exec: Reply = {
    //todo abort PASV listener for the connection

    session.ctrl ! ControlConnection.Poison
    if (session.dataConnection.isEmpty) Reply(221, "Logged out, closing control connection.")
    else Reply(221, "Logged out, closing control connection as soon as data transferred.")
  }
}


// todo FEAT, Help, Opts, Pasv, Site, SiteHelpCommand

case class EprtCommand(param: String, session: Session) extends Command with LoggedIn {
  override def exec: Reply = {
    session.dataOpenerType = None

    param.split(Pattern.quote(param.head.toString)) match {
      case Array(_, protocol, address, port) if protocol == "1" || protocol == "2" =>
        session.dataOpenerType = Some(PortDOT)
        session.dataEndpoint = Some(new InetSocketAddress(address, port.toInt)) //todo port and address check (ip4/ip6)
        Reply(200, "EPRT command successful.")
      case Array(_, _, _, _) =>
        Reply(522, "Network protocol not supported, use (1,2)")
      case _ =>
        Reply(501, "Send correct protocol, IP and port number.")
    }
  }
}

//todo EPSV

case class TvfsCommand(session: Session) extends Command {
  override def exec: Reply = Reply(200, "OK")
}

case class MdtmCommand(param: String, session: Session) extends Command with LoggedIn with FileSystemOps {
  override def exec: Reply =
    param match {
      case "" =>
        Reply(501, "Send file name.")
      case filename =>
        val path = filenamePath(filename)
        val either: Either[File,Reply] =
          try { Left(session.ftpstate.fileSystem.file(path, session)) }
          catch { case e: FileSystemException => Right(handleFsError(e)) }

        if (either.isRight) {
          either.right.get
        } else {
          either.left.get.listFile.collect {
            case file if !file.directory =>
              val sdf = new SimpleDateFormat("yyyyMMddHHmmss")
              sdf.setTimeZone(TimeZone.getTimeZone("GMT"))
              Reply(213, sdf.format(file.modified))
          } getOrElse {
            Reply(550, "File unavailable.")
          }
        }
    }
}

// todo Mlsd, Mlst, Size
