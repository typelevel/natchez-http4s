
val scala212Version        = "2.12.12"
val scala213Version        = "2.13.5"
val scala30PreviousVersion = "3.0.0-M3"
val scala30Version         = "3.0.0-RC1"

val catsVersion       = "2.4.2"
val catsEffectVersion = "2.3.3"

// Global Settings
lazy val commonSettings = Seq(

  // Publishing
  organization := "org.tpolecat",
  licenses    ++= Seq(("MIT", url("http://opensource.org/licenses/MIT"))),
  homepage     := Some(url("https://github.com/tpolecat/natchez-http4s")),
  developers   := List(
    Developer("tpolecat", "Rob Norris", "rob_norris@mac.com", url("http://www.tpolecat.org"))
  ),

  // Headers
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(HeaderLicense.Custom(
    """|Copyright (c) 2021 by Rob Norris
       |This software is licensed under the MIT License (MIT).
       |For more information see LICENSE or https://opensource.org/licenses/MIT
       |""".stripMargin
    )
  ),

  // Compilation
  scalaVersion       := scala213Version,
  crossScalaVersions := Seq(scala212Version, scala213Version, scala30PreviousVersion, scala30Version),
  Compile / console / scalacOptions --= Seq("-Xfatal-warnings", "-Ywarn-unused:imports"),
  Compile / doc     / scalacOptions --= Seq("-Xfatal-warnings"),
  Compile / doc     / scalacOptions ++= Seq(
    "-groups",
    "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
    "-doc-source-url", "https://github.com/tpolecat/natchez-http4s/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),
  libraryDependencies ++= Seq(
    compilerPlugin("org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full),
  ).filterNot(_ => isDotty.value),

  // Add some more source directories
  unmanagedSourceDirectories in Compile ++= {
    val sourceDir = (sourceDirectory in Compile).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case Some((2, _))  => Seq(sourceDir / "scala-2")
      case _             => Seq()
    }
  },

  // Also for test
  unmanagedSourceDirectories in Test ++= {
    val sourceDir = (sourceDirectory in Test).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case Some((2, _))  => Seq(sourceDir / "scala-2")
      case _             => Seq()
    }
  },

  // dottydoc really doesn't work at all right now
  Compile / doc / sources := {
    val old = (Compile / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  },

)

// root project
commonSettings
crossScalaVersions := Nil
publish / skip     := true

lazy val http4s = project
  .in(file("modules/http4s"))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    publish / skip := isDotty.value,
    name        := "natchez-http4s",
    description := "Natchez middleware for http4s.",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "natchez-core"  % "0.0.20",
      "org.http4s"   %% "http4s-core"   % "0.21.15",
      "org.http4s"   %% "http4s-client" % "0.21.15",
    ).filterNot(_ => isDotty.value)
  )

lazy val examples = project
  .in(file("modules/examples"))
  .dependsOn(http4s)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings)
  .settings(
    publish / skip       := true,
    name                 := "natchez-http4s-examples",
    description          := "Example programs for Natchez-Http4s.",
    scalacOptions        -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "natchez-jaeger"      % "0.0.20",
      "org.http4s"   %% "http4s-dsl"          % "0.21.15",
      "org.http4s"   %% "http4s-ember-server" % "0.21.15",
      "org.slf4j"     % "slf4j-simple"        % "1.7.30",
    ).filterNot(_ => isDotty.value)
  )

lazy val docs = project
  .in(file("modules/docs"))
  .dependsOn(http4s)
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(ParadoxSitePlugin)
  .enablePlugins(GhpagesPlugin)
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(
    scalacOptions      := Nil,
    git.remoteRepo     := "git@github.com:tpolecat/natchez-http4s.git",
    ghpagesNoJekyll    := true,
    publish / skip     := true,
    paradoxTheme       := Some(builtinParadoxTheme("generic")),
    version            := version.value.takeWhile(_ != '+'), // strip off the +3-f22dca22+20191110-1520-SNAPSHOT business
    paradoxProperties ++= Map(
      "scala-versions"            -> (crossScalaVersions in http4s).value.map(CrossVersion.partialVersion).flatten.distinct.map { case (a, b) => s"$a.$b"} .mkString("/"),
      "org"                       -> organization.value,
      "scala.binary.version"      -> s"2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "core-dep"                  -> s"${(http4s / name).value}_2.${CrossVersion.partialVersion(scalaVersion.value).get._2}",
      "version"                   -> version.value,
      "scaladoc.natchez.base_url" -> s"https://static.javadoc.io/org.tpolecat/natchez-core_2.13/${version.value}",
    ),
    mdocIn := (baseDirectory.value) / "src" / "main" / "paradox",
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
    mdocExtraArguments := Seq("--no-link-hygiene"), // paradox handles this
  )

