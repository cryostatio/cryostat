#!/usr/bin/env bash

set -e

DIR="$(dirname "$(readlink -f "$0")")"
cd "${DIR}/.."

CRYOSTAT_VERSION="${CRYOSTAT_VERSION:-$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)}"

# Package the main Cryostat project to get full classpath with all dependencies
# This enables complete type resolution including external library types: the generator
# resolves against target/classes and target/quarkus-app/lib/main.
# Skip Quinoa (frontend build) to speed up the process
# update.bash already produces both of those and sets SKIP_APP_BUILD to avoid paying for
# a second full build; a standalone invocation still builds for itself.
if [ "${SKIP_APP_BUILD:-false}" != "true" ]; then
    echo "Packaging Cryostat project for full classpath resolution (skipping frontend build)..."
    ./mvnw -B clean package -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true -Dlicense.skip=true -Dquarkus.quinoa=disabled
else
    echo "Reusing existing application build for classpath resolution."
fi

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
    "${CRYOSTAT_VERSION}" || true
