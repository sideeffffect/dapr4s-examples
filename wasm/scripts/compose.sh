#!/usr/bin/env bash
# Compose an example component with the dapr-wasm-components wasi-http outbound + inbound providers (0.7.2, pulled
# from OCI by wkg), using the upstream compose.sh/compose.wac pinned at the v0.7.2 tag (never `main`).
#   wasm/scripts/compose.sh <app.wasm> <composed.wasm>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app="$1"
out="$2"
ref="v0.7.2"
dir="${XDG_CACHE_HOME:-$HOME/.cache}/dapr4s-examples/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
# The providers are pulled here and passed by path: since 2026-10-15 dapr-wasm-components publishes every
# version under ghcr.io/sideeffffect/dapr-wasm-components/<module> and the flat
# ghcr.io/sideeffffect/dapr-wasm-components-<module> packages, which compose.sh (every tag up to v0.7.4) pulls,
# are deleted.
for d in outbound inbound; do
  f="$dir/dapr_wasm_components_wasi_http_$d.wasm"
  [[ -s "$f" ]] || wkg oci pull "ghcr.io/sideeffffect/dapr-wasm-components/wasi-http-$d:0.7.2" -o "$f" >&2
done
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" \
  --outbound "$dir/dapr_wasm_components_wasi_http_outbound.wasm" \
  --inbound "$dir/dapr_wasm_components_wasi_http_inbound.wasm"
wasm-tools validate "$out"
