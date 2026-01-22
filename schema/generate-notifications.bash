#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

echo "Generating WebSocket notification schema..."

# Build the schema generator submodule
echo "Building schema generator..."
cd schema-generator
../mvnw -B clean package -DskipTests -Dspotless.check.skip=true
cd ..

# Run the schema generator using the fat JAR
echo "Running schema generator..."
java -jar schema-generator/target/notification-schema-generator.jar \
    src/main/java \
    "${DIR}/notifications.yaml"

echo "Successfully generated ${DIR}/notifications.yaml"