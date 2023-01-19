#!/bin/sh

set -xe

DIR="$(dirname "$(readlink -f "$0")")"

if [ -z "$IMAGE_REPOSITORY" ]; then
    IMAGE_REPOSITORY=cryostat
fi

cleanKind() {
    kind delete cluster
}

while [ "$#" -ne 0 ]; do
    case "$1" in
        kind)
            cleanKind
            kind create cluster
            kind load docker-image \
                "quay.io/${IMAGE_REPOSITORY}/cryostat3:dev" \
                "quay.io/${IMAGE_REPOSITORY}/cryostat3-db:dev"
            ;;
        unkind)
            cleanKind
            ;;
        generate)
            sh "${DIR}/../../db/build.sh"
            kompose convert -o "${DIR}" -f "${DIR}/../../container-compose.yml"
            ;;
        apply)
            kubectl apply -f "${DIR}/*.yaml"
            kubectl patch -p "{\"spec\":{\"template\":{\"spec\":{\"\$setElementOrder/containers\":[{\"name\":\"db\"}],\"containers\":[{\"image\":\"quay.io/$IMAGE_REPOSITORY/cryostat3-db:dev\",\"name\":\"db\"}]}}}}" deployment/db
            kubectl wait \
                --for condition=available \
                --timeout=5m \
                deployment db
            kubectl patch -p "{\"spec\":{\"template\":{\"spec\":{\"\$setElementOrder/containers\":[{\"name\":\"cryostat\"}],\"containers\":[{\"image\":\"quay.io/$IMAGE_REPOSITORY/cryostat3:dev\",\"name\":\"cryostat\"}]}}}}" deployment/cryostat
            kubectl wait \
                --for condition=available \
                --timeout=5m \
                deployment cryostat
            echo "!!!! You must now 'kubectl edit deployment cryostat' and set the values for QUARKUS_MINIO_ACCESS_KEY, QUARKUS_MINIO_ACCESS_SECRET"
            ;;
        clean)
            kubectl delete -f "${DIR}/*.yaml"
            ;;
        forward)
            sh -c '(sleep 1 ; xdg-open http://localhost:9001 ; xdg-open http://localhost:8181 ; xdg-open http://localhost:8989)&'
            if ! kubectl multiforward smoketest; then
                echo "Run the following to expose the applications:"
                echo "kubectl port-forward svc/cryostat 8181"
                echo "kubectl port-forward svc/minio 9001"
                echo "kubectl port-forward svc/pgadmin 8989"
            fi
            ;;
        *)
            echo "Usage: $0 [clean|generate]"
            exit 1
            ;;
    esac
    shift
done
