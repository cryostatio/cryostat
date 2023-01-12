#!/bin/sh

set -xe

DIR="$(dirname "$(readlink -f "$0")")"

# kind create cluster
# kind load docker-image quay.io/cryostat/cryostat3{,-db}:latest
kompose convert -o "${DIR}" -f "${DIR}/../container-compose.yml"
kubectl apply -f "${DIR}"
kubectl multiforward smoketest
