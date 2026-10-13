#!/usr/bin/env bash
# Compose an example component with the dapr-wasm-components wasi-http outbound + inbound providers (0.7.0, pulled
# from OCI by wkg), using the upstream compose.sh/compose.wac pinned at the v0.7.0 tag (never `main`).
#   wasm/scripts/compose.sh <app.wasm> <composed.wasm>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wasm="$(cd "$here/.." && pwd)"
app="$1"
out="$2"
ref="v0.7.0"
dir="$wasm/target/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
# Upstream compose.sh at v0.7.0 exits 1 even on success: `tmp` is assigned inside resolve(), which runs in a $(...)
# subshell, so the EXIT trap's `[[ -n "$tmp" ]] && rm ...` sees an empty `tmp` and returns 1. (Later commits on
# main exit 0, but we pin the tag that matches the 0.7.0 WIT and providers.) Its exit code is therefore ignored, and
# success is judged by the output: it must exist and validate.
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --tag 0.7.0 || true
[[ -s "$out" ]] && wasm-tools validate "$out" || { echo "compose.sh: composition of $app failed" >&2; exit 1; }
