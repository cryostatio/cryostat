#!/bin/sh

set -x
set -e

cleanup() {
    docker-compose \
        -f ./smoketest/compose/db.yml \
        -f ./smoketest/compose/s3-minio.yml \
        -f ./smoketest/compose/sample-apps.yml \
        -f ./smoketest/compose/cryostat.yml \
        down --volumes --remove-orphans
    podman kill hoster || true
    > ~/.hosts
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
#     -v "${HOME}/.hosts:/tmp/hosts" \
#     dvdarias/docker-hoster

setupUserHosts() {
    > ~/.hosts
    echo "localhost s3" >> ~/.hosts
    echo "localhost db" >> ~/.hosts
    echo "localhost db-viewer" >> ~/.hosts
    echo "localhost cryostat" >> ~/.hosts
    echo "localhost vertx-fib-demo-1" >> ~/.hosts
    echo "localhost quarkus-test-agent" >> ~/.hosts
}
setupUserHosts

# TODO add switches for picking S3 backend, sample apps, etc.
docker-compose \
    -f ./smoketest/compose/db.yml \
    -f ./smoketest/compose/s3-minio.yml \
    -f ./smoketest/compose/sample-apps.yml \
    -f ./smoketest/compose/cryostat.yml \
    up
