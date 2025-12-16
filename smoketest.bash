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

PRECREATE_BUCKETS=${PRECREATE_BUCKETS:-metadata,archivedrecordings,archivedreports,eventtemplates,probes,threaddumps,heapdumps}

LOG_LEVEL=0
CRYOSTAT_HTTP_HOST=${CRYOSTAT_HTTP_HOST:-cryostat}
CRYOSTAT_HTTP_PORT=${CRYOSTAT_HTTP_PORT:-8080}
USE_PROXY=${USE_PROXY:-true}
DEPLOY_GRAFANA=${DEPLOY_GRAFANA:-true}
DRY_RUN=${DRY_RUN:-false}
USE_TLS=${USE_TLS:-true}
SAMPLE_APPS_USE_TLS=${SAMPLE_APPS_USE_TLS:-true}
ENFORCE_AGENT_TLS=${ENFORCE_AGENT_TLS:-false}

display_usage() {
    echo "Usage:"
    column -t -s% <<< "
    %-h% print this Help text.
    %-O% Offline mode, do not attempt to pull container images that are already present on this machine.
    %-p% disable auth Proxy.
    %-s [none,$(ls -1 ${DIR}/compose/s3-*.yml | xargs basename -a | grep -v none | sort | cut -d/ -f2 | cut -d. -f1 | cut -d- -f2 | paste -sd ',' -)]% S3 implementation to spin up (default \"seaweed\").
    %-G% exclude Grafana dashboard and jfr-datasource from deployment.
    %-r [replicas]% configure a cryostat-reports sidecar instance(s). Optional argument is the number of replicas, which defaults to 1.
    %-t [all|comma-list]% include sample applications for Testing. Leave blank or use \"all\" to deploy everything, otherwise use a comma-separated list from: $(find ${DIR}/compose/sample_apps -type f -name '*.yml' -exec basename {} \; | cut -d. -f1 | grep -v https | sort | tr '\n' ',' | sed 's/,$//')
    %-A% disable TLS on sample applications' Agents.
    %-V% do not discard data storage Volumes on exit.
    %-X% deploy additional development aid tools.
    %-c [/path/to/binary]% use specified Container engine (default \"\$(command -v podman)\").
    %-b% open a Browser tab for each running service's first mapped port (ex. auth proxy login, database viewer).
    %-n% do Not apply configuration changes, instead emit the compose YAML that would have been used to stdout.
    %-k% disable TLS (inseKure) on the auth proxy.
    %-v% enable Verbose logging. Can be passed multiple times to increase verbosity.
    "
}

s3=seaweed
container_engine="$(command -v podman)"
while getopts "hs:prGtAOVXc:bnkv" opt; do
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
                    for sample in ${nextopt//,/ }; do
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
            ENFORCE_AGENT_TLS=false
            ;;
        O)
            PULL_IMAGES=false
            ;;
        v)
            LOG_LEVEL=$((LOG_LEVEL+1))
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
            nextopt=${!OPTIND}
            if [[ -n $nextopt && $nextopt != -* ]] ; then
                OPTIND=$((OPTIND + 1))
                REPORTS_REPLICAS="${nextopt}"
            else
                # there is no nextopt, ie `-r` was passed and there is no argument or the next argument is another flag
                REPORTS_REPLICAS=1
            fi
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
if [ $LOG_LEVEL = 0 ]; then
    CRYOSTAT_LOG_LEVEL=INFO
elif [ $LOG_LEVEL = 1 ]; then
    CRYOSTAT_LOG_LEVEL=DEBUG
elif [ $LOG_LEVEL = 2 ]; then
    CRYOSTAT_LOG_LEVEL=TRACE
else
    CRYOSTAT_LOG_LEVEL=ALL
fi
export CRYOSTAT_LOG_LEVEL
export CRYOSTAT_HTTP_HOST
export CRYOSTAT_HTTP_PORT
export GRAFANA_DASHBOARD_EXT_URL
export DATABASE_GENERATION
export CRYOSTAT_PROXY_PORT
export CRYOSTAT_PROXY_PROTOCOL
export REPORTS_REPLICAS

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

if [ -z "${S3_INSTANCE_ID}" ]; then
    S3_INSTANCE_ID="$(openssl rand -hex 5)"
fi
export S3_INSTANCE_ID

function bucketname() {
    echo "${1}-$(echo "${AWS_ACCESS_KEY_ID}" | head -c 6)-${S3_REGION}-${S3_INSTANCE_ID}" | tr -s '-'
}

if [ ! "${DRY_RUN}" = "true" ]; then
    set -xe
    CRYOSTAT_JAVA_OPTS=$( cat <<-END
-XX:StartFlightRecording=filename=/tmp/,name=onstart,settings=default,disk=true,maxage=5m
-XX:StartFlightRecording=filename=/tmp/,name=startup,settings=profile,disk=true,duration=30s
-Dcom.sun.management.jmxremote.autodiscovery=true
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9091
-Dcom.sun.management.jmxremote.rmi.port=9091
-Djava.rmi.server.hostname=127.0.0.1
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.local.only=false
END
)
    export CRYOSTAT_JAVA_OPTS
else
    CRYOSTAT_STORAGE_BUCKETS_ARCHIVES_NAME="$(bucketname 'archives')"
    export CRYOSTAT_STORAGE_BUCKETS_ARCHIVES_NAME
    CRYOSTAT_STORAGE_BUCKETS_EVENT_TEMPLATES_NAME="$(bucketname 'eventtemplates')"
    export CRYOSTAT_STORAGE_BUCKETS_EVENT_TEMPLATES_NAME
    CRYOSTAT_STORAGE_BUCKETS_PROBE_TEMPLATES_NAME="$(bucketname 'probetemplates')"
    export CRYOSTAT_STORAGE_BUCKETS_PROBE_TEMPLATES_NAME
    CRYOSTAT_STORAGE_BUCKETS_HEAP_DUMPS_NAME="$(bucketname 'heapdumps')"
    export CRYOSTAT_STORAGE_BUCKETS_HEAP_DUMPS_NAME
    CRYOSTAT_STORAGE_BUCKETS_THREAD_DUMPS_NAME="$(bucketname 'threaddumps')"
    export CRYOSTAT_STORAGE_BUCKETS_THREAD_DUMPS_NAME
    CRYOSTAT_STORAGE_BUCKETS_METADATA_NAME="$(bucketname 'metadata')"
    export CRYOSTAT_STORAGE_BUCKETS_METADATA_NAME
fi

CMD=()
for file in "${FILES[@]}"; do
    CMD+=(-f "${file}")
done

createProxyCfgVolume() {
    "${container_engine}" volume create auth_proxy_cfg
    "${container_engine}" container create --name proxy_cfg_helper -v auth_proxy_cfg:/tmp registry.access.redhat.com/ubi9/ubi-micro
    local cfg
    cfg="$(mktemp)"
    chmod 644 "${cfg}"
    envsubst '$STORAGE_PORT' < "${DIR}/compose/${AUTH_PROXY_ALPHA_CONFIG_FILE}.yml" > "${cfg}"
    htpasswd="$(mktemp)"
    htpasswd -ibB "${htpasswd}" "${CRYOSTAT_USER:-user}" "${CRYOSTAT_PASS:-pass}"
    "${container_engine}" cp "${htpasswd}" proxy_cfg_helper:/tmp/auth_proxy_htpasswd
    "${container_engine}" cp "${cfg}" proxy_cfg_helper:/tmp/auth_proxy_alpha_config.yml
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export auth_proxy_cfg > auth_proxy_cfg.tar.gz
    fi
    rm "${htpasswd}"
}

createProxyCertsVolume() {
    "${container_engine}" volume create auth_proxy_certs
    "${container_engine}" container create --name proxy_certs_helper -v auth_proxy_certs:/certs registry.access.redhat.com/ubi9/ubi-micro
    if [ -f "${DIR}/compose/auth_certs/certificate.pem" ] && [ -f "${DIR}/compose/auth_certs/private.key" ]; then
        chmod 644 "${DIR}/compose/auth_certs/private.key"
        "${container_engine}" cp "${DIR}/compose/auth_certs/certificate.pem" proxy_certs_helper:/certs/certificate.pem
        "${container_engine}" cp "${DIR}/compose/auth_certs/private.key" proxy_certs_helper:/certs/private.key
    fi
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export auth_proxy_certs > auth_proxy_certs.tar.gz
    fi
}

createLocalstackCfgVolume() {
    "${container_engine}" volume create localstack_cfg
    "${container_engine}" container create --name localstack_cfg_helper -v localstack_cfg:/tmp registry.access.redhat.com/ubi9/ubi-micro
    "${container_engine}" cp "${DIR}/compose/localstack_buckets.sh" localstack_cfg_helper:/tmp
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export localstack_cfg > localstack_cfg.tar.gz
    fi
}

createJmxTlsCertVolume() {
    "${container_engine}" volume create jmxtls_cfg
    "${container_engine}" container create --name jmxtls_cfg_helper -v jmxtls_cfg:/truststore registry.access.redhat.com/ubi9/ubi-micro
    if [ -d "${DIR}/truststore" ]; then
        "${container_engine}" cp "${DIR}/truststore" jmxtls_cfg_helper:/truststore
    fi
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export jmxtls_cfg > jmxtls_cfg.tar.gz
    fi
}

createEventTemplateVolume() {
    "${container_engine}" volume create templates
    "${container_engine}" container create --name templates_helper -v templates:/templates registry.access.redhat.com/ubi9/ubi-micro
    if [ -d "${DIR}/templates" ]; then
        "${container_engine}" cp "${DIR}/templates" templates_helper:/templates
    fi
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export templates > templates.tar.gz
    fi
}

createProbeTemplateVolume() {
    "${container_engine}" volume create probes
    "${container_engine}" container create --name probes_helper -v probes:/probes registry.access.redhat.com/ubi9/ubi-micro
    if [ -d "${DIR}/probes" ]; then
        "${container_engine}" cp "${DIR}/probes" probes_helper:/probes
    fi
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export probes > probes.tar.gz
    fi
}

createCredentialVolume() {
    "${container_engine}" volume create credentials
    "${container_engine}" container create --name credentials_helper -v credentials:/credentials registry.access.redhat.com/ubi9/ubi-micro
    if [ -d "${DIR}/credentials" ]; then
        "${container_engine}" cp "${DIR}/credentials" credentials_helper:/credentials
    fi
    if [ "${DRY_RUN}" = "true" ]; then
        "${container_engine}" volume export credentials > credentials.tar.gz
    fi
}

createVolumes() {
    if [ "${USE_PROXY}" = "true" ]; then
        createProxyCfgVolume
    fi
    if [ "${USE_PROXY}" = "true" ] && [ "${USE_TLS}" = "true" ]; then
        sh "${DIR}/compose/auth_certs/generate.sh"
        createProxyCertsVolume
    fi
    if [ "${s3}" = "localstack" ]; then
        createLocalstackCfgVolume
    fi
    createJmxTlsCertVolume
    createEventTemplateVolume
    createProbeTemplateVolume
    createCredentialVolume
}

cleanupVolumes() {
    if [ "${USE_PROXY}" = "true" ]; then
        ${container_engine} rm proxy_cfg_helper || true
        ${container_engine} rm proxy_certs_helper || true
        ${container_engine} volume rm auth_proxy_cfg || true
        ${container_engine} volume rm auth_proxy_certs || true
    fi
    if [ "${s3}" = "localstack" ]; then
        ${container_engine} rm localstack_cfg_helper || true
        ${container_engine} volume rm localstack_cfg || true
    fi
    ${container_engine} rm jmxtls_cfg_helper || true
    ${container_engine} volume rm jmxtls_cfg || true
    ${container_engine} rm templates_helper || true
    ${container_engine} volume rm templates || true
    ${container_engine} rm probes_helper || true
    ${container_engine} volume rm probes || true
    ${container_engine} rm credentials_helper || true
    ${container_engine} volume rm credentials || true
}

if [ "${KEEP_VOLUMES}" != "true" ]; then
    cleanupVolumes 1>&2
fi
if [ "${DRY_RUN}" = "true" ]; then
    set +xe
    "${container_engine}" compose \
        "${CMD[@]}" \
        config
    createVolumes 1>&2
    cleanupVolumes 1>&2
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
    if [ "${SAMPLE_APPS_USE_TLS}" = "true" ]; then
        bash "${DIR}/compose/agent_certs/generate-agent-certs.bash" clean || true
    fi
    if [ "${USE_TLS}" = "true" ]; then
        rm "${DIR}/compose/auth_certs/certificate.pem" || true
        rm "${DIR}/compose/auth_certs/private.key" || true
    fi
    cleanupVolumes
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

createVolumes

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
