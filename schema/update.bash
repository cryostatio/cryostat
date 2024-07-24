#!/usr/bin/env bash

DIR="$(dirname "$(readlink -f "$0")")"

"${DIR}"/../mvnw -f "${DIR}/../pom.xml" -B -U clean compile test-compile
"${DIR}"/../mvnw -f "${DIR}/../pom.xml" -B -U -Dmaven.test.skip -Dquarkus.quinoa=false -Dspotless.check.skip -Dquarkus.smallrye-openapi.info-title="Cryostat API" clean quarkus:dev &
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
    if wget --spider http://localhost:8181/health; then
        break
    else
        counter=$((counter + 1))
        sleep "${2:-10}"
    fi
done
wget http://localhost:8181/api -O - | yq -P 'sort_keys(..)' > "${DIR}/openapi.yaml"
wget http://localhost:8181/api/v3/graphql/schema.graphql -O "${DIR}/schema.graphql"
