#!/usr/bin/env bash

set -e

DIR="$(dirname "$(realpath "$0")")"
source "${DIR}/genpass.bash"

SSL_KEYSTORE_PASS="$(genpass)"

echo "$SSL_KEYSTORE_PASS" > "$SSL_KEYSTORE_PASS_FILE"

trap "cd -" EXIT
cd "$CONF_DIR"

# create a new keystore with a bogus cert, then delete the cert, to produce an empty keystore
keytool -genkeypair -v \
    -alias tmp \
    -dname "cn=cryostat, o=Cryostat, c=CA" \
    -storetype PKCS12 \
    -validity 180 \
    -keyalg RSA \
    -storepass "$SSL_KEYSTORE_PASS" \
    -keystore "$SSL_KEYSTORE"
keytool -delete -v \
    -alias tmp \
    -storepass "$SSL_KEYSTORE_PASS" \
    -keystore "$SSL_KEYSTORE"

chmod 664 "${SSL_KEYSTORE}"
chmod 640 "${SSL_KEYSTORE_PASS_FILE}"
