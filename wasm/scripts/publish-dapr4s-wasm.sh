#!/usr/bin/env bash
# Publish the dapr4s Wasm-component layer to ~/.ivy2/local from the commit pinned in wasm/dapr4s-wasm.env:
#   1. clone/fetch dapr4s into $DAPR4S_DIR (default: wasm/target/dapr4s) at DAPR4S_COMMIT (full history: the
#      sbt-dynver version needs the tags);
#   2. build the patched scala-wasm linker (dapr4s scripts/wasm/build-patched-linker.sh; idempotent);
#   3. `sbt -Ddapr4s.wasm.coreRelease=$DAPR4S_CORE_RELEASE publishLocal` in dapr4s/wasm (RELEASE mode: the layer
#      depends on Maven Central dapr4s_sjs1_3:$DAPR4S_CORE_RELEASE, not on dapr4s's sources);
#   4. check that the published version is DAPR4S_WASM_VERSION (what wasm/project/plugins.sbt resolves).
# Idempotent: skips 1-3 when that version is already in ~/.ivy2/local (FORCE=1 republishes).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wasm="$(cd "$here/.." && pwd)"
# shellcheck source=../dapr4s-wasm.env
source "$wasm/dapr4s-wasm.env"
ivy="${IVY_LOCAL:-$HOME/.ivy2/local}/com.github.sideeffffect"
if [[ -d "$ivy/dapr4s-wasm_sjs1_3/$DAPR4S_WASM_VERSION" && -d "$ivy/sbt-dapr4s-wasm/scala_2.12/sbt_1.0/$DAPR4S_WASM_VERSION" && "${FORCE:-0}" != 1 ]]; then
  echo "publish-dapr4s-wasm.sh: $DAPR4S_WASM_VERSION already in ~/.ivy2/local (FORCE=1 to republish)"
  exit 0
fi
dir="${DAPR4S_DIR:-$wasm/target/dapr4s}"
if [[ ! -d "$dir/.git" ]]; then
  git clone --quiet https://github.com/sideeffffect/dapr4s.git "$dir"
fi
git -C "$dir" fetch --quiet --tags origin "$DAPR4S_COMMIT" 2>/dev/null || git -C "$dir" fetch --quiet --tags origin
git -C "$dir" checkout --quiet --force "$DAPR4S_COMMIT"
"$dir/scripts/wasm/build-patched-linker.sh"
got="$(cd "$dir/wasm" && sbt -batch -Ddapr4s.wasm.coreRelease="$DAPR4S_CORE_RELEASE" publishLocal 'print wallWasm/version' | tail -1 | tr -d '[:space:]')"
if [[ "$got" != "$DAPR4S_WASM_VERSION" ]]; then
  echo "publish-dapr4s-wasm.sh: dapr4s $DAPR4S_COMMIT published $got, but dapr4s-wasm.env pins $DAPR4S_WASM_VERSION" >&2
  exit 1
fi
echo "publish-dapr4s-wasm.sh: published the dapr4s wasm layer $got (on dapr4s_sjs1_3 $DAPR4S_CORE_RELEASE)"
