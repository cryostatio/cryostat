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

if [ -z "$KEYSTORE_PASS" ]; then
    KEYSTORE_PASS="$(genpass)"
fi
function importKeyCert() {
    if [ -n "$TLS_CLIENT_CERT_DIR" ]; then
        KEYSTORE_PATH="$CONF_DIR/keystore.p12"

        # create a new keystore with a bogus cert, then delete the cert, to produce an empty keystore
        keytool -genkeypair -v \
            -alias tmp \
            -dname "cn=cryostat, o=Cryostat, c=CA" \
            -storetype PKCS12 \
            -validity 180 \
            -keyalg RSA \
            -storepass "$KEYSTORE_PASS" \
            -keystore "$KEYSTORE_PATH"
        keytool -delete -v \
            -alias tmp \
            -storepass "$KEYSTORE_PASS" \
            -keystore "$KEYSTORE_PATH"

        find "$TLS_CLIENT_CERT_DIR" -type f -name "*.pem" -o -name "*.p12" -o -name "*.jks" | while IFS= read -r cert; do
            echo "Importing certificate $cert to $KEYSTORE_PATH"
            keytool -importcert -v \
                -noprompt \
                -alias "imported-$(basename "$cert")" \
                -trustcacerts \
                -keystore "$KEYSTORE_PATH" \
                -file "$cert" \
                -storepass "$KEYSTORE_PASS" 2>&1 || true
        done
    fi
}
importKeyCert

function importKeyStore() {
    if [ -n "$KEYSTORE_PATH" ]; then
        echo "Using TLS keystore at $KEYSTORE_PATH"
        FLAGS+=("-Djavax.net.ssl.keyStore=$KEYSTORE_PATH")

        if [ -n "$KEYSTORE_PASS" ]; then
            FLAGS+=("-Djavax.net.ssl.keyStorePassword=$KEYSTORE_PASS")
        fi
    fi
}
importKeyStore

export JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND} ${FLAGS[*]}"
exec $1
