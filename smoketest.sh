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
}
trap cleanup EXIT
cleanup

# TODO add switches for picking S3 backend, sample apps, etc.
docker-compose \
    -f ./smoketest/compose/db.yml \
    -f ./smoketest/compose/s3-minio.yml \
    -f ./smoketest/compose/sample-apps.yml \
    -f ./smoketest/compose/cryostat.yml \
    up
