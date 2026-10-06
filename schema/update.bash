#!/usr/bin/env bash

# Regenerates the committed API schema documents.
#
# A single application build feeds all three generators, which then run concurrently:
#   - OpenAPI       reads target/generated/openapi.yaml, written during augmentation
#   - GraphQL       reads target/classes
#   - Notifications resolves types against target/classes and target/quarkus-app/lib/main

set -euo pipefail

DIR="$(dirname "$(readlink -f "$0")")"
ROOT="$(dirname "${DIR}")"
MVNW="${ROOT}/mvnw"

if ! command -v yq > /dev/null; then
    echo "yq is required to normalize the OpenAPI document" >&2
    exit 1
fi

LOGS="$(mktemp -d)"
trap 'rm -rf "${LOGS}"' EXIT

echo "Resolving build versions..."
DEPS="$(mktemp)"
trap 'rm -rf "${LOGS}" "${DEPS}"' EXIT
"${MVNW}" -B -q dependency:list \
    -DincludeGroupIds=io.smallrye \
    -DincludeArtifactIds=smallrye-graphql \
    -DoutputFile="${DEPS}"
SMALLRYE_GRAPHQL_VERSION="$(sed -n 's/.*io\.smallrye:smallrye-graphql:jar:\([^:[:space:]]*\).*/\1/p' "${DEPS}" | head -1)"
if [ -z "${SMALLRYE_GRAPHQL_VERSION}" ]; then
    echo "Could not determine the io.smallrye:smallrye-graphql version" >&2
    exit 1
fi
CRYOSTAT_VERSION="$("${MVNW}" -B -q -DforceStdout help:evaluate -Dexpression=project.version)"
export CRYOSTAT_VERSION
echo "Cryostat ${CRYOSTAT_VERSION}, smallrye-graphql-maven-plugin ${SMALLRYE_GRAPHQL_VERSION}"

echo "Building application..."
"${MVNW}" -B \
    -Dquarkus.quinoa=false \
    -Dquarkus.container-image.build=false \
    -Dmaven.test.skip \
    -Dspotless.check.skip \
    -Dspotbugs.skip \
    -Dquarkus.smallrye-openapi.store-schema-directory=target/generated \
    -Dquarkus.smallrye-openapi.info-title="Cryostat API" \
    clean package

generate_openapi() {
    yq -P 'del(.servers) | sort_keys(..)' "${ROOT}/target/generated/openapi.yaml" > "${DIR}/openapi.yaml"
}

generate_graphql() {
    # The plugin indexes target/classes by default; dependency jars must be opted in so
    # that types from libcryostat and cryostat-core resolve.
    "${MVNW}" -B "io.smallrye:smallrye-graphql-maven-plugin:${SMALLRYE_GRAPHQL_VERSION}:generate-schema" \
        -DincludeDependencies=true \
        -DincludeDependenciesScopes=compile,system,runtime,provided
    cp "${ROOT}/target/generated/schema.graphql" "${DIR}/schema.graphql"
}

generate_notifications() {
    SKIP_APP_BUILD=true "${DIR}/generate-notifications.bash"
}

pids=()
names=()
start() {
    local name="$1"
    shift
    "$@" > "${LOGS}/${name}.log" 2>&1 &
    pids+=("$!")
    names+=("${name}")
}

echo "Generating schemas..."
start openapi generate_openapi
start graphql generate_graphql
start notifications generate_notifications

status=0
for i in "${!pids[@]}"; do
    name="${names[$i]}"
    if wait "${pids[$i]}"; then
        result="ok"
    else
        result="FAILED"
        if [ "${name}" = "notifications" ]; then
            result="FAILED (ignored)"
        else
            status=1
        fi
    fi
    echo "=== ${name}: ${result} ==="
    cat "${LOGS}/${name}.log"
done

exit "${status}"
