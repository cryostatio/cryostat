#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

echo "Generating WebSocket notification schema..."

# Check if target/classes exists, if not compile first
if [ ! -d "target/classes/io/cryostat/schema" ]; then
    echo "Compiling schema generator classes..."
    ./mvnw -B compiler:compile \
        -Dspotless.check.skip=true \
        -Dmaven.test.skip=true \
        -Dmaven.main.skip=false
fi

# Run the notification schema generator using exec:java
# This avoids the generate-resources phase that causes git version issues
echo "Running schema generator..."
./mvnw -B exec:java \
  -Dexec.mainClass="io.cryostat.schema.NotificationSchemaGenerator" \
  -Dexec.args="src/main/java ${DIR}/notifications.yaml" \
  -Dexec.classpathScope=compile \
  -Dspotless.check.skip=true \
  -Dmaven.test.skip=true

echo "Successfully generated ${DIR}/notifications.yaml"