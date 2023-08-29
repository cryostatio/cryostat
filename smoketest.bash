#!/usr/bin/env bash

if ! command -v yq; then
    echo "No 'yq' found"
    exit 1
fi

FILES=(
    ./smoketest/compose/db.yml
    ./smoketest/compose/cryostat.yml
)

PULL_IMAGES=true
CLEAN_VOLUMES=false

display_usage() {
    echo "Usage:"
    echo -e "\t-O \t\t\t\tOffline mode, do not attempt to pull container images."
    echo -e "\t-s [minio|localstack]\t\tS3 implementation to spin up. (default \"minio\")"
    echo -e "\t-g \t\t\t\tinclude Grafana dashboard and jfr-datasource in deployment."
    echo -e "\t-t \t\t\t\tinclude sample applications for Testing."
    echo -e "\t-V \t\t\t\tdelete data storage Volumes on exit."
    echo -e "\t-X \t\t\t\tdeploy additional debugging tools."
}

s3=minio
while getopts "s:gtOVX" opt; do
    case $opt in
        s)
            s3="${OPTARG}"
            ;;
        g)
            FILES+=('./smoketest/compose/cryostat-grafana.yml' './smoketest/compose/jfr-datasource.yml')
            ;;
        t)
            FILES+=('./smoketest/compose/sample-apps.yml')
            ;;
        O)
            PULL_IMAGES=false
            ;;
        V)
            CLEAN_VOLUMES=true
            ;;
        X)
            FILES+=('./smoketest/compose/db-viewer.yml')
            ;;
        *)
            display_usage
            exit 1
            ;;
    esac
done

if [ "${s3}" = "minio" ]; then
    FILES+=('./smoketest/compose/s3-minio.yml')
elif [ "${s3}" = "localstack" ]; then
    FILES+=('./smoketest/compose/s3-localstack.yml')
else
    echo "Unknown S3 selection: ${s3}"
    display_usage
    exit 2
fi

set -xe

CMD=()
for file in "${FILES[@]}"; do
    CMD+=(-f "${file}")
done

cleanup() {
    DOWN_FLAGS=('--remove-orphans')
    if [ "${CLEAN_VOLUMES}" = "true" ]; then
        DOWN_FLAGS+=(--volumes)
    fi
    docker-compose \
        "${CMD[@]}" \
        down "${DOWN_FLAGS[@]}"
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

if [ "${PULL_IMAGES}" = "true" ]; then
    sh db/build.sh
    IMAGES=()
    for file in "${FILES[@]}" ; do
        images="$(yq '.services.*.image' "${file}" | grep -v null)"
        for img in ${images}; do
          IMAGES+=("${img}")
        done
    done
    docker pull "${IMAGES[@]}" || true
fi

docker-compose \
    "${CMD[@]}" \
    up

