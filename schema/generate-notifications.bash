#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

CRYOSTAT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)

# Compile the main Cryostat project to ensure dependencies are available for type resolution
echo "Compiling Cryostat project for dependency resolution..."
./mvnw -B clean compile -DskipTests -Dspotless.check.skip=true

# Build the schema generator tool
echo "Building notification schema generator..."
pushd schema-generator
../mvnw -B clean package -DskipTests -Dspotless.check.skip=true
popd

# Generate the notifications schema
echo "Generating notifications schema..."
java -jar schema-generator/target/notification-schema-generator.jar \
    src/main/java \
    "${DIR}/notifications.yaml" \
    "${CRYOSTAT_VERSION}"