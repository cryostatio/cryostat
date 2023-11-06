#!/usr/bin/env bash

if ! command -v yq; then
    echo "No 'yq' found"
    exit 1
fi

FILES=(
    ./smoketest/compose/db.yml
)

PULL_IMAGES=${PULL_IMAGES:-true}
KEEP_VOLUMES=${KEEP_VOLUMES:-false}
OPEN_TABS=${OPEN_TABS:-false}

display_usage() {
    echo "Usage:"
    echo -e "\t-h\t\t\t\tprint this Help text."
    echo -e "\t-O\t\t\t\tOffline mode, do not attempt to pull container images."
    echo -e "\t-s [minio|localstack]\t\tS3 implementation to spin up (default \"minio\")."
    echo -e "\t-g\t\t\t\tinclude Grafana dashboard and jfr-datasource in deployment."
    echo -e "\t-t\t\t\t\tinclude sample applications for Testing."
    echo -e "\t-V\t\t\t\tdo not discard data storage Volumes on exit."
    echo -e "\t-X\t\t\t\tdeploy additional development aid tools."
    echo -e "\t-c [podman|docker]\t\tUse Podman or Docker Container Engine (default \"podman\")."
    echo -e "\t-b\t\t\t\tOpen a Browser tab for each running service's first mapped port (ex. Cryostat web client, Minio console)"
}

s3=minio
ce=podman
while getopts "hs:gtOVXcb" opt; do
    case $opt in
        h)
            display_usage
            exit 0
            ;;
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
            KEEP_VOLUMES=true
            ;;
        X)
            FILES+=('./smoketest/compose/db-viewer.yml')
            ;;
        c)
            ce="${OPTARG}"
            ;;
        b)
            OPEN_TABS=true
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

if [ "${ce}" = "podman" ]; then
    FILES+=('./smoketest/compose/cryostat.yml')
elif [ "${ce}" = "docker" ]; then
    FILES+=('./smoketest/compose/cryostat_docker.yml')
else
    echo "Unknown Container Engine selection: ${ce}"
    display_usage
    exit 2
fi

set -xe

CMD=()
for file in "${FILES[@]}"; do
    CMD+=(-f "${file}")
done

PIDS=()

HOSTSFILE="${HOSTSFILE:-$HOME/.hosts}"

cleanup() {
    set +xe
    DOWN_FLAGS=('--remove-orphans')
    if [ "${KEEP_VOLUMES}" != "true" ]; then
        DOWN_FLAGS+=('--volumes')
    fi
    docker-compose \
        "${CMD[@]}" \
        down "${DOWN_FLAGS[@]}"
    # podman kill hoster || true
    truncate -s 0 "${HOSTSFILE}"
    for i in "${PIDS[@]}"; do
        kill -0 "${i}" && kill "${i}"
    done
    set -xe
}
trap cleanup EXIT
cleanup

setupUserHosts() {
    # FIXME this is broken: it puts the containers' bridge-internal IP addresses
    # into the user hosts file, but these IPs are in a subnet not reachable from the host.
    # podman run \
    #     --detach \
    #     --rm  \
    #     --name hoster \
    #     --user=0 \
    #     --security-opt label=disable \
    #     -v "${XDG_RUNTIME_DIR}/podman/podman.sock:/tmp/docker.sock:Z" \
    #     -v "${HOME}/.hosts:/tmp/hosts" \
    #     dvdarias/docker-hoster
    #
    # This requires https://github.com/figiel/hosts to work. See README.
    truncate -s 0 "${HOSTSFILE}"
    for file in "${FILES[@]}" ; do
        hosts="$(yq '.services.*.hostname' "${file}" | grep -v null | sed -e 's/^/localhost /')"
        echo "${hosts}" >> "${HOSTSFILE}"
    done
}
setupUserHosts

openBrowserTabs() {
    # TODO find a way to use 'podman wait --condition=healthy $containerId' instead of polling with curl
    set +xe
    local urls=()
    for file in "${FILES[@]}"; do
        yaml="$(yq '.services.* | [{"host": .hostname, "ports": .ports}]' "${file}")"
        length="$(echo "${yaml}" | yq 'length')"
        for (( i=0; i<"${length}"; i+=1 ))
        do
            host="$(echo "${yaml}" | yq ".[${i}].host" | grep -v null)"
            port="$(echo "${yaml}" | yq ".[${i}].ports[0]" | grep -v null | cut -d: -f1)"
            if [ -n "${host}" ] && [ -n "${port}" ]; then
                urls+=("http://${host}:${port}")
            fi
        done
    done
    set -xe
    echo "Service URLs:" "${urls[@]}"
    for url in "${urls[@]}"; do
        (
            until timeout 1s curl -s -f -o /dev/null "${url}"
            do
                sleep 5
            done
            xdg-open "${url}"
            echo "Opened ${url} in default browser."
        ) &
        PIDS+=($!)
    done
}
if [ "${OPEN_TABS}" = "true" ]; then
    openBrowserTabs
fi

if [ "${PULL_IMAGES}" = "true" ]; then
    IMAGES=()
    for file in "${FILES[@]}" ; do
        images="$(yq '.services.*.image' "${file}" | grep -v null)"
        for img in ${images}; do
          IMAGES+=("${img}")
        done
    done
    ${ce} pull "${IMAGES[@]}" || true
fi

docker-compose \
    "${CMD[@]}" \
    up
