name := "fs2-tcp"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.0.0-RC1",
  "org.typelevel" %% "cats-effect" % "0.8",
  "co.fs2" %% "fs2-core" % "0.10.1",
  "co.fs2" %% "fs2-io" % "0.10.1"
)