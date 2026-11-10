#!/usr/bin/env bash
# Compose an example component with the dapr-wasm-components wasi-http outbound + inbound providers (0.7.5, pulled
# from OCI by wkg), using the upstream compose.sh/compose.wac pinned at the v0.7.5 tag (never `main`);
# it pulls the providers from ghcr.io/sideeffffect/dapr-wasm-components/<module> (the OCI names since 0.7.4).
#   wasm/scripts/compose.sh <app.wasm> <composed.wasm>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="$1"
out="$2"
ref="v0.7.5"
dir="${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s-examples/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --tag 0.7.5
wasm-tools validate "$out"
