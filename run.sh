#!/bin/sh

set -x
set -e

if [ -z "${MVN}" ]; then
    MVN="$(which mvn)"
fi

getPomProperty() {
    if command -v xpath > /dev/null 2>&1 ; then
        xpath -q -e "project/properties/$1/text()" pom.xml
    elif command -v mvnd > /dev/null 2>&1 ; then
        mvnd help:evaluate -o -B -q -DforceStdout -Dexpression="$1"
    else
        mvn help:evaluate -o -B -q -DforceStdout -Dexpression="$1"
    fi
}

cleanup() {
    podman pod stop cryostat-pod
    podman pod rm cryostat-pod
}
trap cleanup EXIT

printf "\n\nRunning cryostat ...\n\n"

if ! podman pod exists cryostat-pod; then
    podman pod create \
        --hostname cryostat \
        --name cryostat-pod \
        --publish 9091:9091 \
        --publish 8181:8181
fi

# do: $ podman system service -t 0
# or do: $ systemctl --user start podman.socket
# to create the podman.sock to volume-mount into the container
#
# to check the podman socket is reachable and connectable within the container:
# $ podman exec -it cryo /bin/sh
# sh-4.4# curl -v -s --unix-socket /run/user/0/podman/podman.sock http://d:80/v3.0.0/libpod/info
#
# run as root (uid 0) within the container - with rootless podman this meansif [ -z "${MVN}" ]; then

podman run \
    --pod cryostat-pod \
    --name cryostat \
    --user 0 \
    --health-cmd 'curl --fail http://localhost:8181/health/liveness || exit 1' \
    --label io.cryostat.discovery="true" \
    --label io.cryostat.jmxHost="localhost" \
    --label io.cryostat.jmxPort="0" \
    --label io.cryostat.jmxUrl="service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi" \
    -v "${XDG_RUNTIME_DIR}"/podman/podman.sock:/run/user/0/podman/podman.sock:Z \
    --security-opt label=disable \
    -e CRYOSTAT_PODMAN_ENABLED="true" \
    -e CRYOSTAT_JDP_ENABLE="true" \
    -e QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION="drop-and-create" \
    -e QUARKUS_DATASOURCE_DB_KIND="postgresql" \
    -e QUARKUS_DATASOURCE_USERNAME="cryostat3" \
    -e QUARKUS_DATASOURCE_PASSWORD="cryostat3" \
    -e QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:5432/cryostat3" \
    -e STORAGE_BUCKETS_ARCHIVES_NAME="archivedrecordings" \
    -e QUARKUS_S3_ENDPOINT_OVERRIDE="http://s3:9000" \
    -e QUARKUS_S3_PATH_STYLE_ACCESS="true" \
    -e QUARKUS_S3_AWS_REGION="us-east-1" \
    -e QUARKUS_S3_AWS_CREDENTIALS_TYPE="default" \
    -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-minioroot}" \
    -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-minioroot}" \
    -e JAVA_OPTS_APPEND="-XX:+FlightRecorder -XX:StartFlightRecording=name=onstart,settings=default,disk=true,maxage=5m -Dcom.sun.management.jmxremote.autodiscovery=true -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9091 -Dcom.sun.management.jmxremote.rmi.port=9091 -Djava.rmi.server.hostname=127.0.0.1 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.local.only=false" \
    --rm -it "quay.io/cryostat/cryostat3:dev" "$@" 2>&1 | tee cryostat-run.log
