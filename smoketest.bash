#!/usr/bin/env bash

if ! command -v yq >/dev/null 2>&1 ; then
    echo "No 'yq' found"
    exit 1
fi

DIR="$(dirname "$(readlink -f "$0")")"
export DIR

FILES=(
    "${DIR}/compose/db.yml"
)

USE_USERHOSTS=${USE_USERHOSTS:-true}
PULL_IMAGES=${PULL_IMAGES:-true}
KEEP_VOLUMES=${KEEP_VOLUMES:-false}
OPEN_TABS=${OPEN_TABS:-false}

PRECREATE_BUCKETS=${PRECREATE_BUCKETS:-archivedrecordings,archivedreports,eventtemplates,probes}

CRYOSTAT_HTTP_HOST=${CRYOSTAT_HTTP_HOST:-cryostat}
CRYOSTAT_HTTP_PORT=${CRYOSTAT_HTTP_PORT:-8080}
USE_PROXY=${USE_PROXY:-true}
DEPLOY_GRAFANA=${DEPLOY_GRAFANA:-true}
DRY_RUN=${DRY_RUN:-false}
USE_TLS=${USE_TLS:-true}
SAMPLE_APPS_USE_TLS=${SAMPLE_APPS_USE_TLS:-true}

display_usage() {
    echo "Usage:"
    echo -e "\t-h\t\t\t\t\t\tprint this Help text."
    echo -e "\t-O\t\t\t\t\t\tOffline mode, do not attempt to pull container images."
    echo -e "\t-p\t\t\t\t\t\tdisable auth Proxy."
    echo -e "\t-s [seaweed|minio|cloudserver|localstack]\tS3 implementation to spin up (default \"seaweed\")."
    echo -e "\t-G\t\t\t\t\t\texclude Grafana dashboard and jfr-datasource from deployment."
    echo -e "\t-r\t\t\t\t\t\tconfigure a cryostat-Reports sidecar instance"
    echo -e "\t-t [all|comma-list]\t\t\t\tinclude sample applications for Testing. Leave blank or use 'all' to deploy everything, otherwise use a comma-separated list from:\n\t\t\t\t\t\t\t\t$(find "${DIR}/compose/sample_apps" -type f -name '*.yml' -exec basename {} \; | cut -d. -f1 | grep -v https | sort | tr '\n' ',' | sed 's/,$//')"
    echo -e "\t-A\t\t\t\t\t\tdisable TLS on sample applications' Agents."
    echo -e "\t-V\t\t\t\t\t\tdo not discard data storage Volumes on exit."
    echo -e "\t-X\t\t\t\t\t\tdeploy additional development aid tools."
    echo -e "\t-c [/path/to/binary]\t\t\t\tUse specified Container Engine (default \"\$(command -v podman)\")."
    echo -e "\t-b\t\t\t\t\t\topen a Browser tab for each running service's first mapped port (ex. auth proxy login, database viewer)"
    echo -e "\t-n\t\t\t\t\t\tdo Not apply configuration changes, instead emit the compose YAML that would have been used to stdout."
    echo -e "\t-k\t\t\t\t\t\tdisable TLS on the auth proxy."
}

s3=seaweed
container_engine="$(command -v podman)"
while getopts "hs:prGtAOVXc:bnk" opt; do
    case $opt in
        h)
            display_usage
            exit 0
            ;;
        p)
            USE_PROXY=false
            ;;
        s)
            s3="${OPTARG}"
            ;;
        G)
            DEPLOY_GRAFANA=false
            ;;
        t)
            nextopt=${!OPTIND}
            deploy_all_samples=false
            if [[ -n $nextopt && $nextopt != -* ]] ; then
                OPTIND=$((OPTIND + 1))
                if [ "${nextopt}" = "all" ]; then
                    deploy_all_samples=true
                else
                    for sample in ${nextopt//,/}; do
                        file="${DIR}/compose/sample_apps/${sample}.yml"
                        if [ ! -f "${file}" ]; then
                            echo "No such sample app file: ${file}"
                            exit 1
                        fi
                        FILES+=("${file}")
                    done
                fi
            else
                # there is no nextopt, ie `-t` was passed and there is no argument or the next argument is another flag
                deploy_all_samples=true
            fi
            if [ "${deploy_all_samples}" = "true" ]; then
                for sample in $(find "${DIR}/compose/sample_apps" -type f -exec basename {} \; | cut -d. -f1 | grep -v https); do
                    FILES+=("${DIR}/compose/sample_apps/${sample}.yml")
                done
            fi
            ;;
        A)
            SAMPLE_APPS_USE_TLS=false
            ;;
        O)
            PULL_IMAGES=false
            ;;
        V)
            KEEP_VOLUMES=true
            DATABASE_GENERATION=update
            ;;
        X)
            FILES+=("${DIR}/compose/db-viewer.yml")
            ;;
        c)
            container_engine="${OPTARG}"
            ;;
        b)
            OPEN_TABS=true
            ;;
        r)
            FILES+=('./compose/reports.yml')
            ;;
        n)
            DRY_RUN=true
            ;;
        k)
            USE_TLS=false
            ;;
        *)
            display_usage
            exit 1
            ;;
    esac
done

if [ "${DEPLOY_GRAFANA}" = "true" ]; then
    FILES+=(
        "${DIR}/compose/cryostat-grafana.yml"
        "${DIR}/compose/jfr-datasource.yml"
    )
fi

if [ "${SAMPLE_APPS_USE_TLS}" = "true" ]; then
    for sample in "${FILES[@]}"; do
        if [[ ! "${sample}" =~ sample_apps ]]; then
            continue
        fi
        cfg="$(echo "${sample}" | sed -r 's/(.*).yml$/\1_https.yml/')"
        if [ -f "${cfg}" ]; then
            FILES+=("${cfg}")
        fi
    done
fi

CRYOSTAT_PROXY_PORT=8080
CRYOSTAT_PROXY_PROTOCOL=http
AUTH_PROXY_ALPHA_CONFIG_FILE=auth_proxy_alpha_config_http
if [ "${USE_PROXY}" = "true" ]; then
    FILES+=("${DIR}/compose/auth_proxy.yml")
    CRYOSTAT_HTTP_HOST=auth
    CRYOSTAT_HTTP_PORT=8181
    if [ "${USE_TLS}" = "true" ]; then
        FILES+=("${DIR}/compose/auth_proxy_https.yml")
        CRYOSTAT_PROXY_PORT=8443
        CRYOSTAT_PROXY_PROTOCOL=https
        AUTH_PROXY_ALPHA_CONFIG_FILE=auth_proxy_alpha_config_https
    fi
else
    FILES+=("${DIR}/compose/no_proxy.yml")
    if [ "${s3}" != "none" ]; then
        FILES+=("${DIR}/compose/s3_no_proxy.yml")
    fi
    if [ "${DEPLOY_GRAFANA}" = "true" ]; then
      FILES+=("${DIR}/compose/grafana_no_proxy.yml")
    fi
    GRAFANA_DASHBOARD_EXT_URL=http://grafana:3000/
fi
export CRYOSTAT_HTTP_HOST
export CRYOSTAT_HTTP_PORT
export GRAFANA_DASHBOARD_EXT_URL
export DATABASE_GENERATION
export CRYOSTAT_PROXY_PORT
export CRYOSTAT_PROXY_PROTOCOL

s3Manifest="${DIR}/compose/s3-${s3}.yml"
if [ ! -f "${s3Manifest}" ]; then
    echo "Unknown S3 selection: ${s3}"
    display_usage
    exit 2
fi
FILES+=("${s3Manifest}")
if [ "${s3}" = "none" ]; then
    STORAGE_PORT="4566"
else
    STORAGE_PORT="$(yq '.services.*.expose[0]' "${s3Manifest}" | grep -v null)"
fi
export STORAGE_PORT
export PRECREATE_BUCKETS

if [ "$(basename "${container_engine}")" = "podman" ]; then
    FILES+=("${DIR}/compose/cryostat.yml")
elif [ "$(basename "${container_engine}")" = "docker" ]; then
    FILES+=("${DIR}/compose/cryostat_docker.yml")
else
    echo "Unknown Container Engine selection: ${container_engine}"
    display_usage
    exit 2
fi

if [ ! "${DRY_RUN}" = "true" ]; then
    set -xe
fi

CMD=()
for file in "${FILES[@]}"; do
    CMD+=(-f "${file}")
done

if [ "${DRY_RUN}" = "true" ]; then
    set +xe
    "${container_engine}" compose \
        "${CMD[@]}" \
        config
    exit 0
fi

PIDS=()

HOSTSFILE="${HOSTSFILE:-$HOME/.hosts}"

cleanup() {
    set +xe
    local downFlags=('--remove-orphans')
    if [ "${KEEP_VOLUMES}" != "true" ]; then
        downFlags=('--volumes')
    fi
    "${container_engine}" compose \
        "${CMD[@]}" \
        down "${downFlags[@]}"
    if [ "${USE_PROXY}" = "true" ]; then
        ${container_engine} rm proxy_cfg_helper || true
        ${container_engine} rm proxy_certs_helper || true
        ${container_engine} volume rm auth_proxy_cfg || true
        ${container_engine} volume rm auth_proxy_certs || true
    fi
    if [ "${SAMPLE_APPS_USE_TLS}" = "true" ]; then
        bash "${DIR}/compose/agent_certs/generate-agent-certs.bash" clean || true
    fi
    if [ "${USE_TLS}" = "true" ]; then
        rm "${DIR}/compose/auth_certs/certificate.pem" || true
        rm "${DIR}/compose/auth_certs/private.key" || true
    fi
    if [ "${s3}" = "localstack" ]; then
        ${container_engine} rm localstack_cfg_helper || true
        ${container_engine} volume rm localstack_cfg || true
    fi
    ${container_engine} rm jmxtls_cfg_helper || true
    ${container_engine} volume rm jmxtls_cfg || true
    ${container_engine} rm templates_helper || true
    ${container_engine} volume rm templates || true
    truncate -s 0 "${HOSTSFILE}"
    for i in "${PIDS[@]}"; do
        kill -0 "${i}" && kill "${i}"
    done
    set -xe
}
trap cleanup EXIT
cleanup

if [ "${SAMPLE_APPS_USE_TLS}" = "true" ]; then
    bash "${DIR}/compose/agent_certs/generate-agent-certs.bash" generate
fi

createProxyCfgVolume() {
    "${container_engine}" volume create auth_proxy_cfg
    "${container_engine}" container create --name proxy_cfg_helper -v auth_proxy_cfg:/tmp busybox
    local cfg
    cfg="$(mktemp)"
    chmod 644 "${cfg}"
    envsubst '$STORAGE_PORT' < "${DIR}/compose/${AUTH_PROXY_ALPHA_CONFIG_FILE}.yml" > "${cfg}"
    "${container_engine}" cp "${DIR}/compose/auth_proxy_htpasswd" proxy_cfg_helper:/tmp/auth_proxy_htpasswd
    "${container_engine}" cp "${cfg}" proxy_cfg_helper:/tmp/auth_proxy_alpha_config.yml
}
if [ "${USE_PROXY}" = "true" ]; then
    createProxyCfgVolume
fi

createProxyCertsVolume() {
    "${container_engine}" volume create auth_proxy_certs
    "${container_engine}" container create --name proxy_certs_helper -v auth_proxy_certs:/certs busybox
    if [ -f "${DIR}/compose/auth_certs/certificate.pem" ] && [ -f "${DIR}/compose/auth_certs/private.key" ]; then
        chmod 644 "${DIR}/compose/auth_certs/private.key"
        "${container_engine}" cp "${DIR}/compose/auth_certs/certificate.pem" proxy_certs_helper:/certs/certificate.pem
        "${container_engine}" cp "${DIR}/compose/auth_certs/private.key" proxy_certs_helper:/certs/private.key
    fi
}
if [ "${USE_PROXY}" = "true" ] && [ "${USE_TLS}" = "true" ]; then
    sh "${DIR}/compose/auth_certs/generate.sh"
    createProxyCertsVolume
fi

createLocalstackCfgVolume() {
    "${container_engine}" volume create localstack_cfg
    "${container_engine}" container create --name localstack_cfg_helper -v localstack_cfg:/tmp busybox
    "${container_engine}" cp "${DIR}/compose/localstack_buckets.sh" localstack_cfg_helper:/tmp
}
if [ "${s3}" = "localstack" ]; then
    createLocalstackCfgVolume
fi

createJmxTlsCertVolume() {
    "${container_engine}" volume create jmxtls_cfg
    "${container_engine}" container create --name jmxtls_cfg_helper -v jmxtls_cfg:/truststore busybox
    if [ -d "${DIR}/truststore" ]; then
        "${container_engine}" cp "${DIR}/truststore" jmxtls_cfg_helper:/truststore
    fi
}
createJmxTlsCertVolume

createEventTemplateVolume() {
    "${container_engine}" volume create templates
    "${container_engine}" container create --name templates_helper -v templates:/templates busybox
    if [ -d "${DIR}/templates" ]; then
        "${container_engine}" cp "${DIR}/templates" templates_helper:/templates
    fi
}
createEventTemplateVolume

setupUserHosts() {
    # This requires https://github.com/figiel/hosts to work. See README.
    truncate -s 0 "${HOSTSFILE}"
    for file in "${FILES[@]}" ; do
        local hosts
        hosts="$(yq '.services.*.hostname' "${file}" | { grep -v null || test $? = 1 ; } | sed -e 's/^/localhost /')"
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
                host="$(echo "${yaml}" | yq ".[${i}].host" | { grep -v null || test $? = 1 ; } )"
                if [ "${host}" = "auth" ]; then
                  host="localhost"
                fi
            else
                host="localhost"
            fi
            port="$(echo "${yaml}" | yq ".[${i}].ports[0]" | { grep -v null || test $? = 1 ; } | envsubst | cut -d: -f1)"
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
        images="$(yq '.services.*.image' "${file}" | { grep -v null || test $? = 1; })"
        for img in ${images}; do
          IMAGES+=("$(eval echo "${img}")")
        done
    done
    "${container_engine}" pull "${IMAGES[@]}" || true
fi

"${container_engine}" compose \
    "${CMD[@]}" \
    up \
        --renew-anon-volumes \
        --remove-orphans \
        --abort-on-container-exit
