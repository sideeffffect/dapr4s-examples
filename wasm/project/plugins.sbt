// sbt-dapr4s-wasm: the build logic every dapr4s WASI-component app needs (the patched scala-wasm linker, the stdlib
// overlay, dependency exclusions, `ModuleKind.WasmComponent` + re-embedding the real WIT world). It brings the
// scala-wasm FORK of sbt-scalajs, which is why this is a separate sbt build next to the Mill one (see ../AGENTS.md
// "WASI-component examples"). Its version is pinned in ../dapr4s-wasm.env (DAPR4S_WASM_VERSION env var overrides).
val dapr4sWasmVersion: String = sys.env
  .get("DAPR4S_WASM_VERSION")
  .filter(_.nonEmpty)
  .getOrElse {
    val env = IO.readLines(file("dapr4s-wasm.env").getAbsoluteFile)
    env
      .collectFirst { case l if l.startsWith("DAPR4S_WASM_VERSION=") => l.stripPrefix("DAPR4S_WASM_VERSION=").trim }
      .getOrElse(sys.error("dapr4s-wasm.env has no DAPR4S_WASM_VERSION"))
  }
addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % dapr4sWasmVersion)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
