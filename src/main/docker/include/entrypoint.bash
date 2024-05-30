#!/bin/bash

set -e

FLAGS=()

function importTrustStores() {
    if [ -z "$CONF_DIR" ]; then
        CONF_DIR="/opt/cryostat.d"
    fi
    if [ -z "$SSL_TRUSTSTORE_DIR" ]; then
        SSL_TRUSTSTORE_DIR="/truststore"
    fi

    if [ ! -d "$SSL_TRUSTSTORE_DIR" ]; then
        echo "$SSL_TRUSTSTORE_DIR does not exist; no certificates to import"
        return 0
    elif [ ! "$(ls -A "$SSL_TRUSTSTORE_DIR")" ]; then
        echo "$SSL_TRUSTSTORE_DIR is empty; no certificates to import"
        return 0
    fi

    SSL_TRUSTSTORE_PASS="$(cat "${SSL_TRUSTSTORE_PASS_FILE:-$CONF_DIR/truststore.pass}")"

    find "$SSL_TRUSTSTORE_DIR" -type f | while IFS= read -r cert; do
        echo "Importing certificate $cert ..."

        keytool -importcert -v \
            -noprompt \
            -alias "imported-$(basename "$cert")" \
            -trustcacerts \
            -keystore "${SSL_TRUSTSTORE:-$CONF_DIR/truststore.p12}" \
            -file "$cert"\
            -storepass "$SSL_TRUSTSTORE_PASS"
    done

    FLAGS+=(
        "-Djavax.net.ssl.trustStore=$SSL_TRUSTSTORE"
        "-Djavax.net.ssl.trustStorePassword=$SSL_TRUSTSTORE_PASS"
    )
}
importTrustStores

export JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND} ${FLAGS[*]}"
exec $1
