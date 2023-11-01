#!/usr/bin/env bash

DIR="$(dirname "$(readlink -f "$0")")"

cat > "${DIR}/db-configmap.yaml" <<< "apiVersion: v1
kind: ConfigMap
metadata:
  name: pgadmin-config
data:
  servers.json: |
    $(yq < "${DIR}/../compose/servers.json")"

yq -i '.spec.template.spec.volumes += {"name": "serverjson", "configMap": {"name": "pgadmin-config", "items": [{"key": "servers.json", "path": "servers.json"}]}}' "${DIR}/db-viewer-deployment.yaml"
yq -i '.spec.template.spec.containers[0].volumeMounts += {"mountPath": "/pgadmin4/servers.json", "name": "serverjson", "subPath": "servers.json"}' "${DIR}/db-viewer-deployment.yaml"
