#!/bin/sh
# shellcheck disable=SC3043

set -x
set -e

if [ -z "${PULL_IMAGES}" ]; then
    PULL_IMAGES="always"
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

runCryostat() {
    exec "./run.sh"
}

runLocalStack() {
    podman run \
        --name s3 \
        --pod cryostat-pod \
        --env SERVICES=s3 \
        --env START_WEB=1 \
        --env AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-aws_access_key_id}" \
        --env AWS_SECRET_ACCESS_KEY"${AWS_SECRET_ACCESS_KEY:-aws_secret_access_key}" \
        --env DEFAULT_REGION="us-east-1" \
        --env PORT_WEB_UI=4577 \
        -v ./localstack:/etc/localstack/init/ready.d:z \
        --rm -d docker.io/localstack/localstack:1.4.0
}

runMinio() {
    podman run \
        --name minio \
        --pod cryostat-pod \
        --health-cmd 'curl --fail http://localhost:9001 || exit 1' \
        --health-interval "10s" \
        --health-retries 3 \
        --health-start-period "10s" \
        --health-timeout "5s" \
        --env MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioroot}" \
        --env MINIO_ROOT_PASSWORD="${AWS_SECRET_ACCESS_KEY:-minioroot}" \
        --env MINIO_DEFAULT_BUCKETS="archivedrecordings" \
        -v minio_data:/data \
        -v minio_certs:/certs \
        --rm -d docker.io/minio/minio:latest server /data --console-address ":9001"
}

runDB() {
    podman build \
        -t cryostat-db \
        --file "/home/miwan/Workspace/cryostat3/db/Dockerfile"

    podman run \
        --name db \
        --pod cryostat-pod \
        --expose "5432" \
        --health-cmd 'pg_isready -U cryostat3 -d cryostat3 || exit 1' \
        --health-interval "10s" \
        --health-retries 3 \
        --health-start-period "10s" \
        --health-timeout "5s" \
        --entrypoint /usr/local/bin/docker-entrypoint.sh \
        --env POSTGRES_USER="cryostat3" \
        --env POSTGRES_PASSWORD="cryostat3" \
        -v postgresql:/var/lib/postgresql/data \
        --rm -d quay.io/cryostat/cryostat3-db:dev postgres -c encrypt.key="${PG_ENCRYPT_KEY:-REPLACEME}" 


    podman run \
        --name db-viewer \
        --pod cryostat-pod \
        --health-cmd 'wget --no-verbose --tries=1 --spider http://localhost:8989 || exit 1' \
        --health-interval "10s" \
        --health-retries 3 \
        --health-start-period "10s" \
        --health-timeout "5s" \
        --env PGADMIN_DEFAULT_EMAIL="admin@cryostat.io" \
        --env PGADMIN_DEFAULT_PASSWORD="${PGADMIN_DEFAULT_PASSWORD:-admin}" \
        --env PGADMIN_LISTEN_PORT=8989 \
        -v pgadmin:/var/lib/pgadmin \
        -v ./servers.json:/pgadmin4/servers.json:z \
        --rm -d docker.io/dpage/pgadmin4:6
}

runSampleApps() {
    podman run \
        --name vertx-fib-demo-1 \
        --pod cryostat-pod \
        --health-cmd 'curl --fail http://localhost:8081 || exit 1' \
        --health-interval "10s" \
        --health-retries 3 \
        --health-start-period "10s" \
        --health-timeout "5s" \
        --env HTTP_PORT=8081 \
        --env JMX_PORT=9093 \
        --env CRYOSTAT_AGENT_APP_NAME="vertx-fib-demo-1" \
        --env CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL="true" \
        --env CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME="false" \
        --env CRYOSTAT_AGENT_WEBSERVER_HOST="sample-app" \
        --env CRYOSTAT_AGENT_WEBSERVER_PORT="8910" \
        --env CRYOSTAT_AGENT_CALLBACK="http://localhost:8910/" \
        --env CRYOSTAT_AGENT_BASEURI="http://cryostat:8181/" \
        --env CRYOSTAT_AGENT_TRUST_ALL="true" \
        --env CRYOSTAT_AGENT_AUTHORIZATION="Basic dXNlcjpwYXNz" \
        --label io.cryostat.discovery="true" \
        --label io.cryostat.jmxHost="localhost" \
        --label io.cryostat.jmxPort="9093" \
        --rm -d quay.io/andrewazores/vertx-fib-demo:0.12.3

    # this config is broken on purpose (missing required env vars) to test the agent's behaviour
    # when not properly set up
    podman run \
        --name quarkus-test-agent \
        --pod cryostat-pod \
        --health-cmd 'curl --fail http://localhost:10010 || exit 1' \
        --health-interval "10s" \
        --health-retries 3 \
        --health-start-period "10s" \
        --health-timeout "5s" \
        --env JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager -javaagent:/deployments/app/cryostat-agent.jar" \
        --env QUARKUS_HTTP_PORT=10010 \
        --env ORG_ACME_CRYOSTATSERVICE_ENABLED="false" \
        --env CRYOSTAT_AGENT_APP_NAME="quarkus-test-agent" \
        --env CRYOSTAT_AGENT_WEBCLIENT_SSL_TRUST_ALL="true" \
        --env CRYOSTAT_AGENT_WEBCLIENT_SSL_VERIFY_HOSTNAME="false" \
        --env CRYOSTAT_AGENT_WEBSERVER_HOST="quarkus-test-agent" \
        --env CRYOSTAT_AGENT_WEBSERVER_PORT="9977" \
        --env CRYOSTAT_AGENT_CALLBACK="http://quarkus-test-agent:9977/" \
        --env CRYOSTAT_AGENT_BASEURI="http://cryostat:8181/" \
        --env CRYOSTAT_AGENT_AUTHORIZATION="Basic dXNlcjpwYXNz" \
        --env CRYOSTAT_AGENT_HARVESTER_PERIOD_MS=30000 \
        --env CRYOSTAT_AGENT_HARVESTER_MAX_FILES=3 \
        --env CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_AGE_MS=60000 \
        --env CRYOSTAT_AGENT_HARVESTER_EXIT_MAX_SIZE_B=153600 \
        --rm -d quay.io/andrewazores/quarkus-test:0.0.11
}

createPod() {
    podman pod create \
        --replace \
        --hostname cryostat \
        --name cryostat-pod \
        --publish 8989:8989 \
        --publish 9000:9000 \
        --publish 9001:9001 \
        --publish 8081:8081 \
        --publish 9977:9977 \
        --publish 10010:10010 
}

destroyPod() {
    podman pod stop cryostat-pod
    podman pod rm cryostat-pod
    > ~/.hosts
}
trap destroyPod EXIT

setupUserHosts() {
    > ~/.hosts
    echo "localhost s3" >> ~/.hosts
    echo "localhost db" >> ~/.hosts
    echo "localhost db-viewer" >> ~/.hosts
    echo "localhost cryostat" >> ~/.hosts
    echo "localhost vertx-fib-demo-1" >> ~/.hosts
    echo "localhost quarkus-test-agent" >> ~/.hosts
}

createPod
setupUserHosts
runDB
runMinio
runSampleApps
runCryostat "$1"