#!/bin/bash

set -e
DIR="$(dirname "$(realpath "$0")")"
source "${DIR}/genpass.bash"

FLAGS=()
if [ -z "$CONF_DIR" ]; then
    CONF_DIR="/opt/cryostat.d"
fi

function importTrustStores() {
    if [ -z "$SSL_TRUSTSTORE_DIR" ]; then
        SSL_TRUSTSTORE_DIR="/truststore"
    fi

    if [ ! -d "$SSL_TRUSTSTORE_DIR" ]; then
        echo "$SSL_TRUSTSTORE_DIR does not exist; no certificates to import into truststore"
        return 0
    elif [ ! "$(ls -A "$SSL_TRUSTSTORE_DIR")" ]; then
        echo "$SSL_TRUSTSTORE_DIR is empty; no certificates to import into truststore"
        return 0
    fi

    SSL_TRUSTSTORE_PASS="$(cat "${SSL_TRUSTSTORE_PASS_FILE:-$CONF_DIR/truststore.pass}")"

    keystore="${SSL_TRUSTSTORE:-$CONF_DIR/truststore.p12}"
    find "$SSL_TRUSTSTORE_DIR" -type f | while IFS= read -r cert; do
        echo "Importing certificate $cert to $keystore ..."

        keytool -importcert -v \
            -noprompt \
            -alias "imported-$(basename "$cert")" \
            -trustcacerts \
            -keystore "$keystore" \
            -file "$cert" \
            -storepass "$SSL_TRUSTSTORE_PASS" 2>&1 || true
    done

    FLAGS+=(
        "-Djavax.net.ssl.trustStore=$SSL_TRUSTSTORE"
        "-Djavax.net.ssl.trustStorePassword=$SSL_TRUSTSTORE_PASS"
    )
}
importTrustStores

SSL_KEYSTORE_PASS="$(cat "${SSL_KEYSTORE_PASS_FILE:-$CONF_DIR/keystore.pass}")"
function importKeyCert() {
    if [ -n "$TLS_CLIENT_CERT_DIR" ]; then
        find "$TLS_CLIENT_CERT_DIR" -type f -name "*.pem" -o -name "*.p12" -o -name "*.jks" | while IFS= read -r cert; do
            echo "Importing certificate $cert to $SSL_KEYSTORE"
            keytool -importcert -v \
                -noprompt \
                -alias "imported-$(basename "$cert")" \
                -trustcacerts \
                -keystore "$SSL_KEYSTORE" \
                -file "$cert" \
                -storepass "$SSL_KEYSTORE_PASS" 2>&1 || true
        done
    fi
}
importKeyCert

function importKeyStore() {
    if [ -f "$SSL_KEYSTORE" ]; then
        echo "Using TLS keystore at $SSL_KEYSTORE"
        FLAGS+=("-Djavax.net.ssl.keyStore=$SSL_KEYSTORE")

        if [ -n "$SSL_KEYSTORE_PASS" ]; then
            FLAGS+=("-Djavax.net.ssl.keyStorePassword=$SSL_KEYSTORE_PASS")
        fi
    fi
}
importKeyStore

export JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND} ${FLAGS[*]}"
exec $1
