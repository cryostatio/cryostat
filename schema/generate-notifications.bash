#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

CRYOSTAT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)

# Package the main Cryostat project to get full classpath with all dependencies
# This enables complete type resolution including external library types
# Skip Quinoa (frontend build) to speed up the process
echo "Packaging Cryostat project for full classpath resolution (skipping frontend build)..."
./mvnw -B clean package -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true -Dlicense.skip=true -Dquarkus.quinoa=disabled

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