#!/usr/bin/env bash
# Render SLIDES.md (Marp + Mermaid) to a self-contained PDF.
#
# Marp does not understand Mermaid natively, so this script pre-renders every
# ```mermaid fenced block to a PNG with the Mermaid CLI (mmdc), rewrites the deck
# to reference those images, then runs Marp to produce the PDF. Everything runs
# offline against the system Chrome.
#
# Requirements:
#   - mmdc  (npm i -g @mermaid-js/mermaid-cli)
#   - npx   (Marp CLI is fetched on demand: @marp-team/marp-cli)
#   - a Chromium/Chrome binary
#
# Usage: ./build-slides.sh [input.md] [output.pdf]
set -euo pipefail
cd "$(dirname "$0")"

SRC="${1:-SLIDES.md}"
OUT="${2:-SLIDES.pdf}"
BUILD=".slides-build"
rm -rf "$BUILD"; mkdir -p "$BUILD"

CHROME="${CHROME_PATH:-$(command -v google-chrome-stable || command -v google-chrome || command -v chromium || command -v chromium-browser || true)}"
if [ -z "$CHROME" ]; then echo "No Chrome/Chromium found; set CHROME_PATH." >&2; exit 1; fi

cat > "$BUILD/puppeteer.json" <<EOF
{ "executablePath": "$CHROME", "args": ["--no-sandbox"] }
EOF

# 1. Extract each ```mermaid block to its own .mmd and replace it in the deck
#    with a Marp image reference (paths are relative to the generated deck).
awk -v build="$BUILD" '
  /^```mermaid[[:space:]]*$/ { inblk=1; n++; f=build"/diagram-"n".mmd"; printf "" > f; next }
  inblk && /^```[[:space:]]*$/ { inblk=0; print "![center](diagram-"n".png)"; next }
  inblk { print >> f; next }
  { print }
' "$SRC" > "$BUILD/deck.md"

# 2. Render every diagram to a high-DPI transparent PNG.
shopt -s nullglob
for f in "$BUILD"/diagram-*.mmd; do
  echo "rendering ${f##*/}"
  mmdc -p "$BUILD/puppeteer.json" -i "$f" -o "${f%.mmd}.png" -s 2 -b transparent
done

# 3. Marp -> PDF (allow local files so the PNGs are embedded).
echo "running marp -> $OUT"
CHROME_PATH="$CHROME" npx --yes @marp-team/marp-cli@latest \
  "$BUILD/deck.md" --pdf --allow-local-files -o "$OUT"

echo "wrote $OUT"
