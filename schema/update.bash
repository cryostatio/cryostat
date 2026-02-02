#!/usr/bin/env bash

set -x

DIR="$(dirname "$(readlink -f "$0")")"

if ! command -v http && ! command -v wget; then
    echo "No HTTPie or wget?"
    exit 1
fi

"${DIR}"/../mvnw -B \
    -Dquarkus.quinoa=false \
    -Dquarkus.log.level=warn \
    -Dquarkus.http.access-log.enabled=false \
    -Dquarkus.hibernate-orm.log.sql=false \
    -Dmaven.test.skip \
    -Dspotless.check.skip \
    -Dquarkus.smallrye-openapi.info-title="Cryostat API" \
    clean quarkus:generate-code compile test-compile quarkus:dev &

pid="$!"
function cleanup() {
    kill $pid
}
trap cleanup EXIT
set +e
sleep "${1:-30}"
counter=0
while true; do
    if [ "${counter}" -gt 10 ]; then
        exit 1
    fi
    if command -v http; then
        if http :8181/health/liveness; then
            break
        else
            counter=$((counter + 1))
            sleep "${2:-10}"
        fi
    elif command -v wget; then
        if wget --tries=1 --spider http://localhost:8181/health/liveness; then
            break
        else
            counter=$((counter + 1))
            sleep "${2:-10}"
        fi
    fi
done
if command -v http; then
    http --pretty=format --body :8181/api | yq -P 'sort_keys(..)' > "${DIR}/openapi.yaml"
    http --pretty=format --body :8181/api/v4/graphql/schema.graphql > "${DIR}/schema.graphql"
elif command -v wget; then
    wget http://localhost:8181/api -O - | yq -P 'sort_keys(..)' > "${DIR}/openapi.yaml"
    wget http://localhost:8181/api/v4/graphql/schema.graphql -O "${DIR}/schema.graphql"
fi
