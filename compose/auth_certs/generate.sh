#!/usr/bin/sh

set -xe

CERTS_DIR="$(dirname "$(readlink -f "$0")")"

openssl req -new -addext "subjectAltName = DNS:auth" -newkey rsa:4096 -x509 -sha256 -days 365 -nodes -out "${CERTS_DIR}/certificate.pem" -keyout "${CERTS_DIR}/private.key" -subj "/C=CA/ST=ON/L=Toronto/O=RedHat/OU=JavaMonitoring/CN=Cryostat"
