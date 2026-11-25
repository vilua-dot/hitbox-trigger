#!/usr/bin/env python3
"""
Serves agent.jar over HTTPS for the native loader to download.
Run this on your server or locally — the loader fetches agent.jar from here.

Usage:
    python serve.py                        # serves on 0.0.0.0:8443
    python serve.py --port 9000            # custom port
    python serve.py --cert cert.pem --key key.pem  # custom TLS cert

Generate a self-signed cert for local dev:
    openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes
"""

import argparse
import http.server
import os
import ssl
import subprocess
import sys

def generate_self_signed(cert_path, key_path):
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048",
        "-keyout", key_path, "-out", cert_path,
        "-days", "365", "-nodes",
        "-subj", "/CN=localhost"
    ], check=True, capture_output=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", default="cert.pem")
    parser.add_argument("--key", default="key.pem")
    parser.add_argument("--jar", default="../agent/build/libs/agent-1.0.0.jar")
    args = parser.parse_args()

    jar_path = os.path.abspath(args.jar)
    if not os.path.exists(jar_path):
        print(f"[!] agent jar not found at {jar_path}")
        print("    build it first: gradle :agent:build")
        sys.exit(1)

    serve_dir = os.path.dirname(jar_path)

    # auto-generate self-signed cert if missing
    if not os.path.exists(args.cert) or not os.path.exists(args.key):
        print("[*] generating self-signed TLS certificate...")
        generate_self_signed(args.cert, args.key)

    os.chdir(serve_dir)

    # rename jar to agent.jar for consistent URL
    target = os.path.join(serve_dir, "agent.jar")
    if jar_path != target and not os.path.exists(target):
        os.symlink(jar_path, target)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(
        os.path.abspath(args.cert),
        os.path.abspath(args.key)
    )

    server = http.server.HTTPServer(("0.0.0.0", args.port), http.server.SimpleHTTPRequestHandler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)

    print(f"[+] serving {jar_path}")
    print(f"[+] https://0.0.0.0:{args.port}/agent.jar")
    print("[+] ctrl+c to stop")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[+] stopped")

if __name__ == "__main__":
    main()
