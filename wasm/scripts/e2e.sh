#!/usr/bin/env bash
# End-to-end test of the WASI-component examples. For each example component:
#   1. compose it with the dapr-wasm-components 0.7.2 wasi-http providers (compose.sh);
#   2. run it in `wasmtime serve` next to a real daprd (`dapr run`) with a Redis state store (Docker);
#   3. call it through daprd's service-invocation API and assert the real output:
#      18-hello-state-shell        `run` -> the same report lines as the JVM example 01
#      19-service-invocation-shell `greet`/`stats` as in example 04's e2e, then 04's unchanged JVM `caller`
#                                  (dapr4s 3.0.0 on the JVM) calls the Wasm greeting-service through daprd.
#
# Needs: Docker, the dapr CLI + daprd (`dapr init --slim`), wasmtime, wasm-tools, wkg, wac, python3, curl, a JDK,
# the built components (wasm/scripts/build.sh) and 04's JVM caller jar (CALLER_JAR, default: Mill's assembly).
# `./mill wasm.e2e` builds everything and runs this script.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wasm="$(cd "$here/.." && pwd)"
repo="$(cd "$wasm/.." && pwd)"
components="$wasm/target/components"
logs="$wasm/target/e2e"
caller_jar="${CALLER_JAR:-$repo/out/04-service-invocation-shell/assembly.dest/out.jar}"
redis=dapr4s-examples-wasm-redis
mkdir -p "$logs"
rm -f "$logs"/*.log

started_redis=0
app_id=""
app_port=""
stop_app() {
  [[ -n "$app_id" ]] && { dapr stop --app-id "$app_id" >/dev/null 2>&1 || true; }
  [[ -n "$app_port" ]] && { pkill -f "^wasmtime serve -S cli --addr 127.0.0.1:$app_port" 2>/dev/null || true; }
  app_id=""
  app_port=""
  sleep 2
}
cleanup() {
  stop_app
  [[ "$started_redis" == 1 ]] && docker rm -f "$redis" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "e2e: FAILED: $*" >&2
  for f in "$logs"/*.log; do [[ -f "$f" ]] && { echo "--- $f (tail)"; tail -40 "$f"; } >&2; done
  exit 1
}

[[ -s "$caller_jar" ]] || fail "04's JVM caller jar not found at $caller_jar (./mill 04-service-invocation-shell.assembly)"
for c in 18-hello-state-shell 19-service-invocation-shell; do
  [[ -s "$components/$c.wasm" ]] || fail "$components/$c.wasm not built (wasm/scripts/build.sh)"
done

if ! docker ps --format '{{.Names}}' | grep -qx "$redis"; then
  docker run -d --rm --name "$redis" -p 26490:6379 redis:7-alpine >/dev/null
  started_redis=1
fi
for _ in $(seq 1 30); do docker exec "$redis" redis-cli PING 2>/dev/null | grep -q PONG && break; sleep 1; done

# start <component> <app-id> <app-port> <dapr-http-port> <dapr-grpc-port>
start() {
  local c="$1"
  app_id="$2"
  app_port="$3"
  http="$4"
  grpc="$5"
  "$here/compose.sh" "$components/$c.wasm" "$components/composed-$c.wasm" >"$logs/compose-$c.log" 2>&1 ||
    fail "$c: compose"
  echo "$c: composed with the dapr-wasm-components 0.7.2 wasi-http providers"
  docker exec "$redis" redis-cli FLUSHALL >/dev/null
  dapr run --app-id "$app_id" --app-port "$app_port" --dapr-http-port "$http" --dapr-grpc-port "$grpc" \
    --resources-path "$wasm/components" -- \
    wasmtime serve -S cli --addr "127.0.0.1:$app_port" --env "DAPR_HTTP_PORT=$http" \
    "$components/composed-$c.wasm" >"$logs/run-$c.log" 2>&1 &
  local up=0
  for _ in $(seq 1 90); do
    grep -q "up and running" "$logs/run-$c.log" && { up=1; break; }
    sleep 1
  done
  [[ "$up" == 1 ]] || fail "$c: daprd + wasmtime serve did not come up"
  sleep 1
  echo "$c: running in wasmtime serve as app '$app_id' next to daprd"
}

# invoke <method> [json-body] -> "<status> <body>"
invoke() {
  local args=(-sS -o "$logs/body" -w '%{http_code}' -X POST "http://localhost:$http/v1.0/invoke/$app_id/method/$1")
  [[ $# -ge 2 ]] && args+=(-H 'content-type: application/json' -d "$2")
  local status
  status="$(curl "${args[@]}")"
  echo "$status $(cat "$logs/body")"
}

expect_line() { # expect_line <text> <exact line>
  grep -qxF -- "$2" <<<"$1" || fail "expected the line '$2' in:"$'\n'"$1"
}
expect_contains() {
  grep -qF -- "$2" <<<"$1" || fail "expected '$2' in:"$'\n'"$1"
}

# ── 18: hello-state ──────────────────────────────────────────────────────────
start 18-hello-state-shell hello-state 18101 3601 50601
read -r status body <<<"$(invoke run)"
[[ "$status" == 200 ]] || fail "run: HTTP $status $body"
report="$(python3 -c 'import json,sys; print("\n".join(json.loads(sys.argv[1])))' "$body")"
echo "18-hello-state-shell: run ->"
sed 's/^/    /' <<<"$report"
# The same assertions as e2e/src/e2e/Example01HelloStateTest.scala (the JVM twin).
expect_line "$report" "saved:        Some(Note(Hello from dapr4s!,1))"
expect_line "$report" "etag save:    ok"
expect_line "$report" "after update: Some(Note(Updated!,2))"
expect_line "$report" "txn note-a:   Some(Note(A,1))"
expect_line "$report" "txn note-b:   Some(Note(B,1))"
expect_line "$report" "txn original: None"
expect_contains "$report" "note-a=Some(Note(A,1))"
expect_contains "$report" "note-b=Some(Note(B,1))"
stop_app

# ── 19: service invocation ───────────────────────────────────────────────────
start 19-service-invocation-shell greeting-service 18102 3602 50602
greeting() { python3 -c 'import json,sys; j=json.loads(sys.argv[1]); print(j["greeting"] + " | from: " + j["from"])' "$1"; }
# The same cases as e2e/src/e2e/Example04ServiceInvocationTest.scala (the JVM twin).
for case in 'en|Alice|Hello, Alice!' 'es|Bob|¡Hola, Bob!' 'fr|Carol|Bonjour, Carol!' 'jp|X|Hi, X!'; do
  IFS='|' read -r lang name want <<<"$case"
  read -r status body <<<"$(invoke greet "{\"name\":\"$name\",\"language\":\"$lang\"}")"
  [[ "$status" == 200 ]] || fail "greet $lang: HTTP $status $body"
  got="$(greeting "$body")"
  echo "19-service-invocation-shell: greet $lang -> $got"
  [[ "$got" == "$want | from: greeting-service" ]] || fail "greet $lang: got '$got', expected '$want'"
done
read -r status body <<<"$(invoke stats)"
[[ "$status" == 200 ]] || fail "stats: HTTP $status $body"
echo "19-service-invocation-shell: stats -> $body"
python3 - "$body" <<'PY' || fail "stats: $body"
import json, sys
j = json.loads(sys.argv[1])
assert j["totalRequests"] == 4, j
assert j["languages"] == ["en", "es", "fr", "jp"], j
PY
read -r status body <<<"$(invoke no-such-method)"
echo "19-service-invocation-shell: no-such-method -> HTTP $status"
[[ "$status" == 404 ]] || fail "no-such-method: HTTP $status, expected 404"

# 04's unchanged JVM caller (dapr4s 3.0.0 on the JVM) against the Wasm greeting-service, through the same daprd.
caller_out="$(DAPR_HTTP_PORT=$http DAPR_GRPC_PORT=$grpc java -cp "$caller_jar" serviceinvocation.caller 2>"$logs/caller-stderr.log")" ||
  fail "04 JVM caller exited non-zero:"$'\n'"$caller_out"
echo "04 JVM caller -> Wasm greeting-service:"
sed 's/^/    /' <<<"$caller_out"
expect_contains "$caller_out" "Hello, Alice!  (from: greeting-service)"
expect_contains "$caller_out" "Hi, Eve!  (from: greeting-service)"
expect_contains "$caller_out" "Total requests: 9"
expect_contains "$caller_out" "invokeHttp no-such-method: HTTP 404"
expect_contains "$caller_out" "invoke no-such-method: InvokeHttpException(HTTP 404)"
stop_app
echo "e2e: OK"
