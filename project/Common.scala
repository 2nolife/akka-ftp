import sbt._
import sbt.Keys._

object Common {

  val settings =
    List(
      // Core settings
      organization := "com.coldcore",
      version := "0.1",
      scalaVersion := Version.scala,
      crossScalaVersions := List(scalaVersion.value),
      scalacOptions ++= List(
        "-unchecked",
        "-deprecation",
        "-language:_",
        "-target:jvm-1.7",
        "-encoding", "UTF-8"
      ),
      unmanagedSourceDirectories in Compile := List((scalaSource in Compile).value),
      unmanagedSourceDirectories in Test := List((scalaSource in Test).value),
      resolvers += "spray" at "http://repo.spray.io/"
    ) :::
    List(
      resolvers += Resolver.url("sbt-releases-repo") artifacts
        "http://repo.typesafe.com/typesafe/ivy-releases/[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
      resolvers += Resolver.url("sbt-plugins-repo") artifacts
        "http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
    )
}
