ThisBuild / tlBaseVersion := "0.6"

val http4sVersion           = "0.23.27"
val natchezVersion          = "0.3.6"
val scala212Version         = "2.12.20"
val scala213Version         = "2.13.14"
val scala3Version           = "3.3.3"
val slf4jVersion            = "2.0.16"
val munitCEVersion          = "2.0.0"
val scalacheckEffectVersion = "2.0.0-M2"

ThisBuild / organization := "org.tpolecat"
ThisBuild / tlSonatypeUseLegacyHost := false
ThisBuild / licenses := Seq(("MIT", url("http://opensource.org/licenses/MIT")))
ThisBuild / homepage := Some(url("https://github.com/tpolecat/natchez-http4s"))
ThisBuild / developers := List(
  Developer("tpolecat", "Rob Norris", "rob_norris@mac.com", url("http://www.tpolecat.org"))
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / startYear := Some(2021)
lazy val commonSettings = Seq(
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom(
    """|Copyright (c) 2021 by Rob Norris
       |This software is licensed under the MIT License (MIT).
       |For more information see LICENSE or https://opensource.org/licenses/MIT
       |""".stripMargin
    )
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "munit-cats-effect"       % munitCEVersion          % Test,
    "org.http4s"    %%% "http4s-dsl"              % http4sVersion           % Test,
    "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectVersion % Test,
  )
)

ThisBuild / scalaVersion := scala213Version
ThisBuild / crossScalaVersions := Seq(scala212Version, scala213Version, scala3Version)

lazy val root = tlCrossRootProject.aggregate(
  http4s,
  examples,
  docs
)

lazy val http4s = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/http4s"))
  .settings(commonSettings)
  .settings(
    name        := "natchez-http4s",
    description := "Natchez middleware for http4s.",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "natchez-core"    % natchezVersion,
      "org.http4s"   %%% "http4s-core"     % http4sVersion,
      "org.http4s"   %%% "http4s-client"   % http4sVersion,
      "org.http4s"   %%% "http4s-server"   % http4sVersion,
      "org.tpolecat" %%% "natchez-testkit" % natchezVersion % Test,
    )
  )

lazy val examples = project
  .in(file("modules/examples"))
  .dependsOn(http4s.jvm)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(
    name                 := "natchez-http4s-examples",
    description          := "Example programs for Natchez-Http4s.",
    tlFatalWarnings      := false,
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "natchez-jaeger"      % natchezVersion,
      "org.http4s"   %% "http4s-dsl"          % http4sVersion,
      "org.http4s"   %% "http4s-ember-server" % http4sVersion,
      "org.slf4j"     % "slf4j-simple"        % slf4jVersion,
    )
  )

lazy val docs = project
  .in(file("modules/docs"))
  .dependsOn(http4s.jvm)
  .enablePlugins(AutomateHeaderPlugin, ParadoxPlugin, ParadoxSitePlugin, GhpagesPlugin, MdocPlugin, NoPublishPlugin)
  .settings(commonSettings)
  .settings(
    scalacOptions      := Nil,
    git.remoteRepo     := "git@github.com:tpolecat/natchez-http4s.git",
    ghpagesNoJekyll    := true,
    publish / skip     := true,
    paradoxTheme       := Some(builtinParadoxTheme("generic")),
    version            := version.value.takeWhile(_ != '+'), // strip off the +3-f22dca22+20191110-1520-SNAPSHOT business
    paradoxProperties ++= Map(
      "scala-versions"            -> (http4s.jvm / crossScalaVersions).value.map(CrossVersion.partialVersion).flatten.distinct.map { case (a, b) => s"$a.$b"} .mkString("/"),
      "org"                       -> organization.value,
      "scala.binary.version"      -> s"2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "core-dep"                  -> s"${(http4s.jvm / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "version"                   -> version.value,
      "scaladoc.natchez.base_url" -> s"https://static.javadoc.io/org.tpolecat/natchez-core_2.13/${version.value}",
    ),
    mdocIn := (baseDirectory.value) / "src" / "main" / "paradox",
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
    mdocExtraArguments := Seq("--no-link-hygiene"), // paradox handles this
  )
