#!/usr/bin/env bash

DIR="$(dirname "$(readlink -f "$0")")"

"${DIR}"/../mvnw -B -U clean compile test-compile
"${DIR}"/../mvnw -B -U -DskipTests -Dspotless.check.skip -Dquarkus.smallrye-openapi.info-title="Cryostat API" clean quarkus:dev &
pid="$!"
set +e
sleep 30
wget --spider http://localhost:8181/health
while [  -ne 0 ]; do !! ; sleep 10 ; done
wget http://localhost:8181/api -O - | yq -P 'sort_keys(..)' > "${DIR}/openapi.yaml"
wget http://localhost:8181/api/v3/graphql/schema.graphql -O "${DIR}/schema.graphql"
kill $pid
