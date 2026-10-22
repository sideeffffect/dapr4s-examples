#!/usr/bin/env bash
# Publish the dapr4s Mill plugin (and, with `all`, the dapr4s wasm artifacts) to ~/.ivy2/local from the dapr4s
# commit pinned in wasm/dapr4s-wasm.env, at the version build.mill's header asks for:
#   wasm/scripts/publish-dapr4s-wasm.sh [plugin|all]     (default: plugin)
#
#   plugin  mill-dapr4s-wasm only. EVERY `./mill` command of this repository needs it: build.mill's header loads it
#           into the meta-build. Needs only git and Java (dapr4s's ./mill downloads its own Mill and JDK).
#   all     + dapr4s-wasm, dapr4s-wasm-glue(-exports), dapr4s-wasm-scalalib-overlay, dapr4s-wasm-linker: what
#           `./mill wasm.e2e` resolves. Also needs wkg, wasm-tools and wit-bindgen-scala (dapr4s's build fetches the
#           WIT world and generates its bindings) and network access to GitHub (the patched linker is compiled from
#           scala-wasm's release commit).
#
# Steps: clone/fetch dapr4s into $DAPR4S_DIR (default: ~/.cache/dapr4s-examples/dapr4s) at DAPR4S_COMMIT (full
# history: the version is derived from git tags), run its Mill `publishLocal` in release mode (the wall depends on
# Maven Central dapr4s_sjs1_3:$DAPR4S_CORE_RELEASE), and check that the published version is the one build.mill pins.
# Idempotent: does nothing when that version is already in ~/.ivy2/local (FORCE=1 republishes).
#
# Once the plugin and the wasm artifacts are released, this script and dapr4s-wasm.env go away: the header pins a
# release from Maven Central.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wasm="$(cd "$here/.." && pwd)"
repo="$(cd "$wasm/.." && pwd)"
what="${1:-plugin}"
# shellcheck source=../dapr4s-wasm.env
source "$wasm/dapr4s-wasm.env"
version="$(sed -nE 's/^\/\/\| mvnDeps:.*mill-dapr4s-wasm::([^"]+)".*/\1/p' "$repo/build.mill")"
[[ -n "$version" ]] || { echo "publish-dapr4s-wasm.sh: no mill-dapr4s-wasm version in build.mill's header" >&2; exit 1; }

ivy="${IVY_LOCAL:-$HOME/.ivy2/local}/com.github.sideeffffect"
modules=(wasm.millPlugin)
artifacts=(mill-dapr4s-wasm_mill1_3)
case "$what" in
plugin) ;;
all)
  modules+=('wasm.lib[release].wall' wasm.glue wasm.glueExports wasm.scalalibOverlay 'wasm.linker[2.13.18]')
  artifacts+=(dapr4s-wasm_sjs1_3 dapr4s-wasm-glue_sjs1_2.13 dapr4s-wasm-glue-exports_sjs1_2.13
    dapr4s-wasm-scalalib-overlay dapr4s-wasm-linker_2.13)
  ;;
*)
  echo "usage: $0 [plugin|all]" >&2
  exit 2
  ;;
esac
missing() {
  for a in "${artifacts[@]}"; do [[ -d "$ivy/$a/$version" ]] || return 0; done
  return 1
}
if ! missing && [[ "${FORCE:-0}" != 1 ]]; then
  echo "publish-dapr4s-wasm.sh: $what $version already in ~/.ivy2/local (FORCE=1 to republish)"
  exit 0
fi

dir="${DAPR4S_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s-examples/dapr4s}"
if [[ ! -d "$dir/.git" ]]; then
  mkdir -p "$(dirname "$dir")"
  git clone --quiet https://github.com/sideeffffect/dapr4s.git "$dir"
fi
git -C "$dir" fetch --quiet --tags origin "$DAPR4S_COMMIT" 2>/dev/null || git -C "$dir" fetch --quiet --tags origin
git -C "$dir" checkout --quiet --force "$DAPR4S_COMMIT"
# dapr4s builds with an unreleased mill-scalablytyped: install it first (idempotent).
"$dir/scripts/upstream-snapshot.sh"
args=()
for m in "${modules[@]}"; do
  [[ ${#args[@]} -gt 0 ]] && args+=(+)
  args+=("$m.publishLocal")
done
(cd "$dir" && DAPR4S_WASM_CORE_VERSION="$DAPR4S_CORE_RELEASE" ./mill --ticker false "${args[@]}")
(cd "$dir" && ./mill --ticker false shutdown >/dev/null 2>&1) || true
if missing; then
  echo "publish-dapr4s-wasm.sh: dapr4s $DAPR4S_COMMIT did not publish $version (pinned in build.mill); found:" >&2
  ls "$ivy/mill-dapr4s-wasm_mill1_3" >&2 || true
  exit 1
fi
echo "publish-dapr4s-wasm.sh: published $what $version (wasm artifacts on dapr4s_sjs1_3 $DAPR4S_CORE_RELEASE)"
