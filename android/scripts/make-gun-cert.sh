#!/usr/bin/env bash
# The phone light gun's HTTPS certificate (GunServer.java). One is kept in
# android/gun so Safari's exception survives app updates; the build makes one
# only if it is missing. Run again before it expires (800 days). iOS refuses, even after "Visit this website", any server certificate
# valid for more than 825 days or without the serverAuth extended key usage,
# so this one lasts 800 days and says it is a TLS server.
set -euo pipefail
out="${1:?usage: make-gun-cert.sh <dir>}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 800 \
    -subj "/CN=m2nexus.local/O=M2 NEXUS" \
    -addext "subjectAltName=DNS:m2nexus.local" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$tmp/key.pem" -outform DER -out "$out/key.der"
openssl x509 -in "$tmp/cert.pem" -outform DER -out "$out/cert.der"
echo "gun certificate: $(openssl x509 -in "$tmp/cert.pem" -noout -enddate)"
