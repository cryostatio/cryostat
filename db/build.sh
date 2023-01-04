#!/bin/bash

DIR="$(dirname "$(readlink -f "$0")")"

podman build -t quay.io/cryostat/cryostat3-db:latest -f "${DIR}/Dockerfile" "${DIR}"
