name := "akka-ftp"

Common.settings

libraryDependencies ++= Dependencies.AkkaFtp

initialCommands := """|import com.coldcore.akkaftp.ftp._""".stripMargin
