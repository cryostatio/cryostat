#!/bin/sh

DIR="$(dirname "$(readlink -f "$0")")"

if [ -z "${BUILDER}" ]; then
    BUILDER=podman
fi

${BUILDER} build --pull -t quay.io/cryostat/cryostat3-db:latest -f "${DIR}/Dockerfile" "${DIR}"
${BUILDER} tag quay.io/cryostat/cryostat3-db:latest quay.io/cryostat/cryostat3-db:dev
