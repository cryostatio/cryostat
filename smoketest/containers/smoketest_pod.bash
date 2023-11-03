#!/usr/bin/env bash

set -x
set -e

HOSTSFILE="${HOSTSFILE:-$HOME/.hosts}"

cleanup() {
    podman-compose --in-pod=1 \
        -f ./smoketest/compose/db.yml \
        -f ./smoketest/compose/s3-minio.yml \
        -f ./smoketest/compose/cryostat-grafana.yml \
        -f ./smoketest/compose/jfr-datasource.yml \
        -f ./smoketest/compose/sample-apps.yml \
        -f ./smoketest/compose/cryostat.yml \
        down --volumes --remove-orphans
    # podman kill hoster || true
    truncate -s 0 "${HOSTSFILE}"
}
trap cleanup EXIT
cleanup

# FIXME this is broken: it puts the containers' bridge-internal IP addresses
# into the user hosts file, but these IPs in a subnet not reachable from the host.
# This requires https://github.com/figiel/hosts to work. See README.
# podman run \
#     --detach \
#     --rm  \
#     --name hoster \
#     --user=0 \
#     --security-opt label=disable \
#     -v "${XDG_RUNTIME_DIR}/podman/podman.sock:/tmp/docker.sock:Z" \
#     -v "${HOSTSFILE}:/tmp/hosts" \
#     dvdarias/docker-hoster

setupUserHosts() {
    truncate -s 0 "${HOSTSFILE}"
    for svc in s3 db db-viewer cryostat vertx-fib-demo-1 quarkus-test-agent; do
        echo "localhost ${svc}" >> "${HOSTSFILE}"
    done
}
setupUserHosts

# TODO add switches for picking S3 backend, sample apps, etc.
podman-compose --in-pod=1 \
    -f ./smoketest/compose/db.yml \
    -f ./smoketest/compose/s3-minio.yml \
    -f ./smoketest/compose/cryostat-grafana.yml \
    -f ./smoketest/compose/jfr-datasource.yml \
    -f ./smoketest/compose/sample-apps.yml \
    -f ./smoketest/compose/cryostat.yml \
    up
