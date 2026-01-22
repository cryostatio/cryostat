#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

CRYOSTAT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)

pushd schema-generator
../mvnw -B clean package -DskipTests -Dspotless.check.skip=true
popd

java -jar schema-generator/target/notification-schema-generator.jar \
    src/main/java \
    "${DIR}/notifications.yaml" \
    "${CRYOSTAT_VERSION}"