#!/bin/sh

set -x

CERTS_DIR=$(realpath "$(dirname "$0")")

SSL_KEYSTORE=cryostat-keystore.p12

SSL_TRUSTSTORE=cryostat-truststore.p12

SSL_KEYSTORE_PASS_FILE=keystore.pass

if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(readlink -f /usr/bin/java | grep -oP '.*?openjdk')
fi

cleanup() {
    cd "$CERTS_DIR"
    rm -f $SSL_TRUSTSTORE $SSL_KEYSTORE $SSL_KEYSTORE_PASS_FILE
    cd -
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

SSL_TRUSTSTORE_PASS=$(genpass)

SSL_KEYSTORE_PASS=$(genpass)

cd "$CERTS_DIR"
trap "cd -" EXIT

if [ -f $SSL_KEYSTORE_PASS_FILE ]; then
    cleanup
fi

echo "$SSL_KEYSTORE_PASS" > $SSL_KEYSTORE_PASS_FILE

keytool \
    -genkeypair -v \
    -alias custom-cryostat \
    -dname "cn=cryostat, o=Cryostat, c=CA" \
    -storetype PKCS12 \
    -validity 365 \
    -keyalg RSA \
    -storepass "$SSL_KEYSTORE_PASS" \
    -keystore "$SSL_KEYSTORE"

keytool \
    -exportcert -v \
    -alias custom-cryostat \
    -keystore "$SSL_KEYSTORE" \
    -storepass "$SSL_KEYSTORE_PASS" \
    -file server.cer

if [ -d "$CERTS_DIR/../truststore" ]; then

    keytool \
    -importkeystore \
    -noprompt \
    -storetype PKCS12 \
    -srckeystore ${JAVA_HOME}/lib/security/cacerts \
    -srcstorepass changeit \
    -destkeystore "$SSL_TRUSTSTORE" \
    -deststorepass "$SSL_TRUSTSTORE_PASS"

    keytool \
        -importcert -v \
        -noprompt \
        -trustcacerts \
        -keystore "$SSL_TRUSTSTORE" \
        -alias selftrust \
        -file server.cer \
        -storepass "$SSL_TRUSTSTORE_PASS"

   mv server.cer "$CERTS_DIR/../truststore/dev-self-signed.cer"
fi
