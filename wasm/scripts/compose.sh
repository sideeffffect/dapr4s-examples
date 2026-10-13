#!/usr/bin/env bash
# Compose an example component with the dapr-wasm-components wasi-http outbound + inbound providers (0.7.1, pulled
# from OCI by wkg), using the upstream compose.sh/compose.wac pinned at the v0.7.1 tag (never `main`).
#   wasm/scripts/compose.sh <app.wasm> <composed.wasm>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wasm="$(cd "$here/.." && pwd)"
app="$1"
out="$2"
ref="v0.7.1"
dir="$wasm/target/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
# Upstream compose.sh exits 0 on success since v0.7.1 (at v0.7.0 its EXIT trap made it exit 1 even on success, so
# callers had to ignore its status). The status is trusted now; the output is still validated.
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --tag 0.7.1
wasm-tools validate "$out"
