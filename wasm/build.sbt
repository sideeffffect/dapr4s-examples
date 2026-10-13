// =================================================================================================
// dapr4s-examples — WASI-component examples (separate sbt build; `./mill wasm.e2e` drives it)
//
// The same pure modules as the JVM examples (../01-hello-state, ../04-service-invocation), compiled
// by the same Scala 3 nightly with the same flags, plus a Wasm "shell" per example that turns the
// pure code into a WASI component for dapr-wasm-components 0.7.0:
//
//   18-hello-state-shell          01's HelloStateApp.run behind an invoke route `run`
//   19-service-invocation-shell   04's CalleeApp.app (greeting-service), called by 04's JVM caller
//
// WHY sbt and not Mill: the scala-wasm fork of Scala.js (`ModuleKind.WasmComponent`, WIT bindings)
// ships only an sbt plugin, and it is a fork of sbt-scalajs with the same classes, so it cannot
// live in a build that also uses mainline Scala.js (examples 15-17). dapr4s keeps its wasm build
// apart for the same reason.
//
// Classpath: Maven Central dapr4s (dapr4s_sjs1_3, Scala.js IR) + the small dapr4s wasm layer from a
// publishLocal of a pinned dapr4s commit (dapr4s-wasm.env). The plugin (project/plugins.sbt)
// provides `dapr4sWasmVersion`, `dapr4sWasmWitExports`, `Dapr4sWasmPlugin`, `Dapr4sWasmComponentPlugin`.
// =================================================================================================

val repoDir = file("..").getAbsoluteFile.getCanonicalFile

// Single source of truth: the Mill build. The dapr4s wasm layer was compiled by the same nightly.
val scalaNightly: String =
  """val ScalaVersion = "([^"]+)"""".r
    .findFirstMatchIn(IO.read(repoDir / "build.mill"))
    .getOrElse(sys.error("../build.mill defines no ScalaVersion"))
    .group(1)

ThisBuild / scalaVersion := scalaNightly
ThisBuild / resolvers += "scala3-nightlies" at "https://repo.scala-lang.org/artifactory/maven-nightlies"
ThisBuild / scalafmtConfig := repoDir / ".scalafmt.conf"

// The same flags as build.mill's Dapr4sModule / PureModule.
val shellFlags = Seq("-experimental")
val pureFlags = shellFlags :+ "-language:experimental.safe"

// Same codec library (and version) as the JVM shells. Its mainline Scala.js dependencies are dropped
// by Dapr4sWasmPlugin's exclusions; the rest links fine on a Wasm-only module kind.
val upickle = Def.setting("com.lihaoyi" %%% "upickle" % "3.3.1")

def srcOnly(dir: File) = Seq(
  Compile / unmanagedSourceDirectories := Seq(dir),
  Test / unmanagedSourceDirectories := Nil,
)

// A pure module of the Mill build, recompiled for the wasm platform against the shared dapr4s API only
// (dapr4s-wasm-core: Central dapr4s_sjs1_3 + DaprComponent) — never against the wall.
def pureModule(dir: String) = Seq(
  name := dir,
  scalacOptions ++= pureFlags,
  libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm-core" % dapr4sWasmVersion,
) ++ srcOnly(repoDir / dir / "src")

// A Wasm shell: the component. `componentWasm` writes target/components/<dir>.wasm.
def componentShell(dir: String) = Seq(
  name := dir,
  scalacOptions ++= shellFlags,
  libraryDependencies ++= Seq(
    "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion,
    dapr4sWasmWitExports(dapr4sWasmVersion),
    upickle.value,
  ),
) ++ srcOnly(file(dir).getAbsoluteFile / "src")

lazy val helloStatePure = project
  .in(file("target/pure/01-hello-state"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(pureModule("01-hello-state"))

lazy val helloStateShell = project
  .in(file("18-hello-state-shell"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(componentShell("18-hello-state-shell"))
  .dependsOn(helloStatePure)

lazy val serviceInvocationPure = project
  .in(file("target/pure/04-service-invocation"))
  .enablePlugins(Dapr4sWasmPlugin)
  .settings(pureModule("04-service-invocation"))

lazy val serviceInvocationShell = project
  .in(file("19-service-invocation-shell"))
  .enablePlugins(Dapr4sWasmComponentPlugin)
  .settings(componentShell("19-service-invocation-shell"))
  .dependsOn(serviceInvocationPure)

lazy val root = project
  .in(file("."))
  .aggregate(helloStatePure, helloStateShell, serviceInvocationPure, serviceInvocationShell)
  .settings(publish / skip := true, publishLocal / skip := true)
