/*
 * Copyright 2019 Todd Burnside
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

val fs2Version        = "3.0.0"
val catsVersion       = "2.5.0"
val catsEffectVersion = "3.0.2"
val logbackVersion    = "1.2.3"
val log4CatsVersion   = "2.0.1"
val ammoniteVersion   = "2.3.8"

name := "gds-prototype"

ThisBuild / version := "0.1"

ThisBuild / organization := "edu.gemini"

ThisBuild / scalaVersion := "2.13.3"

ThisBuild / crossScalaVersions := Seq("2.13.3")

ThisBuild / libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core"       % catsVersion,
  "org.typelevel" %% "cats-effect"     % catsEffectVersion,
  "org.typelevel" %% "log4cats-slf4j"  % log4CatsVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "co.fs2"        %% "fs2-core"        % fs2Version,
  "co.fs2"        %% "fs2-io"          % fs2Version,
  ("com.lihaoyi"  %% "ammonite"        % ammoniteVersion % "test").cross(CrossVersion.full)
)

Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main.main(args) }""")
  Seq(file)
}.taskValue
