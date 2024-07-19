#!/usr/bin/env bash

set -x

CERTS_DIR="$(realpath "$(dirname "$0")")"
TRUSTSTORE_DIR="$CERTS_DIR/../../truststore/"

SSL_KEYSTORE=agent-keystore.p12

SSL_KEYSTORE_PASS_FILE=keystore.pass

AGENT_SERVER_CERT_FILE=agent_server.cer

cleanup() {
    pushd "$CERTS_DIR"
    rm "$SSL_KEYSTORE" "$SSL_KEYSTORE_PASS_FILE" "$AGENT_SERVER_CERT_FILE"
    popd
}

case "$1" in
    clean)
        cleanup
        exit 0
        ;;
    generate)
        ;;
    *)
        echo "Usage: $0 [clean|generate]"
        exit 1
        ;;
esac

set -e

genpass() {
    < /dev/urandom tr -dc _A-Z-a-z-0-9 | head -c32
}

SSL_KEYSTORE_PASS="$(genpass)"

cd "$CERTS_DIR"
trap "cd -" EXIT

echo "$SSL_KEYSTORE_PASS" > "$SSL_KEYSTORE_PASS_FILE"

keytool \
    -genkeypair -v \
    -alias quarkus-cryostat-agent \
    -dname "CN=quarkus-cryostat-agent, O=Cryostat, C=CA" \
    -storetype PKCS12 \
    -validity 365 \
    -keyalg RSA \
    -storepass "$SSL_KEYSTORE_PASS" \
    -keystore "$SSL_KEYSTORE"

keytool \
    -exportcert -v \
    -alias  quarkus-cryostat-agent \
    -keystore "$SSL_KEYSTORE" \
    -storepass "$SSL_KEYSTORE_PASS" \
    -file "$AGENT_SERVER_CERT_FILE"

mkdir -p "${TRUSTSTORE_DIR}" && \
    cp agent_server.cer "${TRUSTSTORE_DIR}"
