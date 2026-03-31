import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scala.sys.process._

val scala3Version = "3.6.3"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.twitch"

// ── Node.js resolution ─────────────────────────────────────────────
// Find a Node >= 20 via nvm or system PATH. Scalawind and Tailwind CLI
// require a modern Node; the nvm default may be too old.

def findNodeBin(): File = {
  val home = System.getProperty("user.home")
  val nvmDir = Option(System.getenv("NVM_DIR")).getOrElse(home + "/.nvm")
  val versionsDir = new File(nvmDir, "versions/node")
  val candidates: Seq[File] = if (versionsDir.isDirectory) {
    versionsDir.listFiles.toSeq
      .filter(_.getName.startsWith("v"))
      .sortBy { f =>
        val parts = f.getName.stripPrefix("v").split('.')
        (parts(0).toInt, parts.lift(1).map(_.toInt).getOrElse(0), parts.lift(2).map(_.toInt).getOrElse(0))
      }
      .reverse
      .map(d => new File(d, "bin"))
      .filter(d => new File(d, "npx").exists())
  } else Seq.empty
  candidates.find { bin =>
    val node = new File(bin, "node")
    val version = Process(Seq(node.absolutePath, "--version")).!!.trim.stripPrefix("v")
    version.split('.').headOption.exists(_.toInt >= 20)
  }.getOrElse {
    val systemNpx = "which npx".!!.trim
    new File(systemNpx).getParentFile
  }
}

lazy val nodeBin: File = findNodeBin()

def runNode(cmd: Seq[String], cwd: File, log: sbt.util.Logger): Int = {
  val path = nodeBin.absolutePath + ":" + Option(System.getenv("PATH")).getOrElse("")
  val env = Seq("PATH" -> path)
  Process(cmd, cwd, env: _*).!(ProcessLogger(s => log.info(s), s => log.error(s)))
}

// ── npm / Tailwind / Scalawind tasks ───────────────────────────────

val npmInstall = taskKey[Unit]("Run npm install if node_modules is missing")
val scalawindGen = taskKey[Seq[File]]("Generate scalawind.scala from tailwind config")
val tailwindBuild = taskKey[File]("Build Tailwind CSS output")

val scalawindOutput = file("modules/frontend/src/main/scala/com/twitch/frontend/scalawind.scala")

ThisBuild / npmInstall := {
  val log = streams.value.log
  val nodeModules = baseDirectory.value / "node_modules"
  if (!nodeModules.exists()) {
    log.info("Running npm install...")
    val npm = new File(nodeBin, "npm").absolutePath
    val exitCode = runNode(Seq(npm, "install"), baseDirectory.value, log)
    if (exitCode != 0) sys.error("npm install failed")
  }
}

// ── Project modules ────────────────────────────────────────────────

lazy val root = project.in(file("."))
  .aggregate(core.jvm, core.js, frontend, backend)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.5.7",
      "io.circe"      %%% "circe-core"   % "0.14.10",
      "io.circe"      %%% "circe-generic" % "0.14.10",
      "io.circe"      %%% "circe-parser" % "0.14.10"
    )
  )

lazy val frontend = project.in(file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(core.js)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("com.twitch.frontend.Main"),
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "calico"     % "0.2.3",
      "org.http4s"     %%% "http4s-dom" % "0.2.11",
      "org.http4s"     %%% "http4s-circe" % "0.23.30"
    ),

    // Generate scalawind.scala before compilation
    scalawindGen := {
      val log = streams.value.log
      npmInstall.value
      if (!scalawindOutput.exists()) {
        log.info("Generating scalawind.scala...")
        val base = (ThisBuild / baseDirectory).value
        val npxPath = new File(nodeBin, "npx").absolutePath
        val exitCode = runNode(
          Seq(npxPath, "scalawind", "generate",
            "-o", scalawindOutput.absolutePath,
            "-p", "com.twitch.frontend"),
          base, log)
        if (exitCode != 0) sys.error("scalawind generate failed")
      }
      Seq(scalawindOutput)
    },
    Compile / sourceGenerators += scalawindGen.taskValue,

    // Build Tailwind CSS before compilation
    tailwindBuild := {
      val log = streams.value.log
      npmInstall.value
      val base = (ThisBuild / baseDirectory).value
      val outDir = base / "modules" / "frontend" / "dist"
      val outFile = outDir / "output.css"
      val inputFile = base / "modules" / "frontend" / "tailwind.css"
      if (!outDir.exists()) outDir.mkdirs()
      if (!outFile.exists() || inputFile.lastModified() > outFile.lastModified()) {
        log.info("Building Tailwind CSS...")
        val npxPath = new File(nodeBin, "npx").absolutePath
        val exitCode = runNode(
          Seq(npxPath, "tailwindcss",
            "-i", inputFile.absolutePath,
            "-o", outFile.absolutePath),
          base, log)
        if (exitCode != 0) sys.error("tailwindcss build failed")
      }
      outFile
    },
    Compile / compile := {
      tailwindBuild.value
      (Compile / compile).value
    }
  )

lazy val backend = project.in(file("modules/backend"))
  .dependsOn(core.jvm)
  .settings(
    name := "backend",
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % "0.23.30",
      "org.http4s"    %% "http4s-ember-client" % "0.23.30",
      "org.http4s"    %% "http4s-dsl"          % "0.23.30",
      "org.http4s"    %% "http4s-circe"        % "0.23.30",
      "ch.qos.logback" % "logback-classic"     % "1.5.16",
      "org.tpolecat"  %% "doobie-core"         % "1.0.0-RC8",
      "org.tpolecat"  %% "doobie-h2"           % "1.0.0-RC8",
      "org.tpolecat"  %% "doobie-hikari"       % "1.0.0-RC8",
      "com.h2database" % "h2"                  % "2.3.232"
    )
  )

// ── Convenience command alias ──────────────────────────────────────

addCommandAlias("dev", ";frontend/fastLinkJS;backend/run")
