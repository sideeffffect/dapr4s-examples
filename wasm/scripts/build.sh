#!/usr/bin/env bash
# Build the example components (wasm/target/components/<example>.wasm) with the separate sbt build in wasm/.
# Needs: sbt, JDK, wkg, wasm-tools, wit-bindgen-scala, the dapr4s wasm layer in ~/.ivy2/local
# (wasm/scripts/publish-dapr4s-wasm.sh) and the patched scala-wasm linker it builds.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/.."
sbt -batch ${SBT_ARGS:-} scalafmtCheckAll scalafmtSbtCheck helloStateShell/componentWasm serviceInvocationShell/componentWasm
