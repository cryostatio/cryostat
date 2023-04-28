#!/bin/bash

DIR="$(dirname "$(readlink -f "$0")")"

podman build --pull=always -t quay.io/cryostat/cryostat3-db:latest -f "${DIR}/Dockerfile" "${DIR}"
podman tag quay.io/cryostat/cryostat3-db:latest quay.io/cryostat/cryostat3-db:dev
