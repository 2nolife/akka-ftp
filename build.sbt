name := "akka-ftp"

Common.settings

libraryDependencies ++= Dependencies.AkkaFtp

lazy val root = (project in file(".")).
  configs(IntegrationTest).
  settings(Defaults.itSettings: _*)

// example: custom command
// unmanagedSourceDirectories in Compile += baseDirectory.value / "examples/custom-command/scala"
