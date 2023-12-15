#!/usr/bin/env bash

if ! command -v yq; then
    echo "No 'yq' found"
    exit 1
fi

DIR="$(dirname "$(readlink -f "$0")")"

FILES=(
    "${DIR}/smoketest/compose/db.yml"
    "${DIR}/smoketest/compose/auth_proxy.yml"
)

USE_USERHOSTS=${USE_USERHOSTS:-true}
PULL_IMAGES=${PULL_IMAGES:-true}
KEEP_VOLUMES=${KEEP_VOLUMES:-false}
OPEN_TABS=${OPEN_TABS:-false}

display_usage() {
    echo "Usage:"
    echo -e "\t-h\t\t\t\t\t\tprint this Help text."
    echo -e "\t-O\t\t\t\t\t\tOffline mode, do not attempt to pull container images."
    echo -e "\t-s [minio|seaweed|cloudserver|localstack]\tS3 implementation to spin up (default \"minio\")."
    echo -e "\t-g\t\t\t\t\t\tinclude Grafana dashboard and jfr-datasource in deployment."
    echo -e "\t-t\t\t\t\t\t\tinclude sample applications for Testing."
    echo -e "\t-V\t\t\t\t\t\tdo not discard data storage Volumes on exit."
    echo -e "\t-X\t\t\t\t\t\tdeploy additional development aid tools."
    echo -e "\t-c [podman|docker]\t\t\t\tUse Podman or Docker Container Engine (default \"podman\")."
    echo -e "\t-b\t\t\t\t\t\tOpen a Browser tab for each running service's first mapped port (ex. Cryostat web client, Minio console)"
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
            FILES+=("${DIR}/smoketest/compose/cryostat-grafana.yml" "${DIR}/smoketest/compose/jfr-datasource.yml")
            ;;
        t)
            FILES+=("${DIR}/smoketest/compose/sample-apps.yml")
            ;;
        O)
            PULL_IMAGES=false
            ;;
        V)
            KEEP_VOLUMES=true
            ;;
        X)
            FILES+=("${DIR}/smoketest/compose/db-viewer.yml")
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

s3Manifest="${DIR}/smoketest/compose/s3-${s3}.yml"

if [ ! -f "${s3Manifest}" ]; then
    echo "Unknown S3 selection: ${s3}"
    display_usage
    exit 2
fi
FILES+=("${s3Manifest}")

if [ "${ce}" = "podman" ]; then
    FILES+=("${DIR}/smoketest/compose/cryostat.yml")
    container_engine="podman"
elif [ "${ce}" = "docker" ]; then
    FILES+=("${DIR}/smoketest/compose/cryostat_docker.yml")
    container_engine="docker"
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
    local downFlags=('--remove-orphans')
    if [ "${KEEP_VOLUMES}" != "true" ]; then
        downFlags=('--volumes')
    fi
    docker-compose \
        "${CMD[@]}" \
        down "${downFlags[@]}"
    ${container_engine} rm proxy_cfg_helper
    ${container_engine} volume rm auth_proxy_cfg
    # podman kill hoster || true
    truncate -s 0 "${HOSTSFILE}"
    for i in "${PIDS[@]}"; do
        kill -0 "${i}" && kill "${i}"
    done
    set -xe
}
trap cleanup EXIT
cleanup

createProxyCfgVolume() {
    "${container_engine}" volume create auth_proxy_cfg
    "${container_engine}" container create --name proxy_cfg_helper -v auth_proxy_cfg:/tmp busybox
    "${container_engine}" cp "${DIR}/smoketest/compose/auth_proxy_htpasswd" proxy_cfg_helper:/tmp/auth_proxy_htpasswd
    "${container_engine}" cp "${DIR}/smoketest/compose/auth_proxy_alpha_config.yaml" proxy_cfg_helper:/tmp/auth_proxy_alpha_config.yaml
}
createProxyCfgVolume

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
        local hosts
        hosts="$(yq '.services.*.hostname' "${file}" | grep -v null | sed -e 's/^/localhost /')"
        echo "${hosts}" >> "${HOSTSFILE}"
    done
}
if [ "${USE_USERHOSTS}" = "true" ]; then
    setupUserHosts
fi

openBrowserTabs() {
    # TODO find a way to use 'podman wait --condition=healthy $containerId' instead of polling with curl
    set +xe
    local urls=()
    for file in "${FILES[@]}"; do
        local yaml
        yaml="$(yq '.services.* | [{"host": .hostname, "ports": .ports}]' "${file}")"
        local length
        length="$(echo "${yaml}" | yq 'length')"
        for (( i=0; i<"${length}"; i+=1 ))
        do
            local host
            local port
            if [ "${USE_USERHOSTS}" = "true" ]; then
                host="$(echo "${yaml}" | yq ".[${i}].host" | grep -v null)"
                if [ "${host}" = "auth" ]; then
                  host="localhost"
                fi
            else
                host="localhost"
            fi
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
            testSvc() {
              timeout 1s curl -s -f -o /dev/null "$1"
              local sc="$?"
              if [ "${sc}" = "0" ] || [ "${sc}" = "22" ]; then
                return 0
              else
                return "${sc}"
              fi
            }
            until testSvc "${url}"
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
