#!/usr/bin/sh

set -xe

CERTS_DIR="$(dirname "$(readlink -f "$0")")"
TRUSTSTORE_DIR="$(dirname "${CERTS_DIR}")"/../truststore

# Generate self-signed certificate for PostgreSQL database server
openssl req -new -addext "subjectAltName = DNS:db,DNS:localhost,IP:127.0.0.1" -newkey rsa:4096 -x509 -sha256 -days 365 -nodes -out "${CERTS_DIR}/certificate.pem" -keyout "${CERTS_DIR}/private.key" -subj "/C=CA/ST=ON/L=Toronto/O=RedHat/OU=JavaMonitoring/CN=db"

# Copy database certificate to truststore directory so Cryostat trusts it
cp "${CERTS_DIR}/certificate.pem" "${TRUSTSTORE_DIR}/database.cer"
