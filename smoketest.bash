#!/usr/bin/env bash

if ! command -v yq; then
    echo "No 'yq' found"
    exit 1
fi

set -x
set -e

# TODO add switches for picking S3 backend, sample apps, etc.
FILES=(
    ./smoketest/compose/db.yml
    ./smoketest/compose/s3-minio.yml
    ./smoketest/compose/cryostat-grafana.yml
    ./smoketest/compose/jfr-datasource.yml
    ./smoketest/compose/sample-apps.yml
    ./smoketest/compose/cryostat.yml
)
CMD=()
for file in "${FILES[@]}"; do
    CMD+=(-f "${file}")
done

cleanup() {
    docker-compose \
        "${CMD[@]}" \
        down --volumes --remove-orphans
    # podman kill hoster || true
    > ~/.hosts
}
trap cleanup EXIT
cleanup

setupUserHosts() {
    # FIXME this is broken: it puts the containers' bridge-internal IP addresses
    # into the user hosts file, but these IPs are in a subnet not reachable from the host.
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
    > ~/.hosts
    for file in "${FILES[@]}" ; do
        hosts="$(yq '.services.*.hostname' "${file}" | grep -v null | sed -e 's/^/localhost /')"
        echo "${hosts}" >> ~/.hosts
    done
}
setupUserHosts

sh db/build.sh
for file in "${FILES[@]}" ; do
    images="$(yq '.services.*.image' "${file}" | grep -v null)"
    echo "${images}" | xargs docker pull || true
done

docker-compose \
    "${CMD[@]}" \
    up

