#!/usr/bin/env bash

podman run \
    --rm -it \
    --mount type=bind,source="$(dirname "$0")/smoketest/compose/auth-proxy-htpasswd",destination=/tmp/.htpasswd,relabel=shared \
    --mount type=bind,source="$(dirname "$0")/smoketest/compose/oauth2-proxy-alpha-config.yaml",destination=/tmp/oauth2-proxy-alpha-config.yaml,relabel=shared \
    --env OAUTH2_PROXY_HTPASSWD_FILE=/tmp/.htpasswd \
    --env OAUTH2_PROXY_HTPASSWD_USER_GROUP=write \
    --env OAUTH2_PROXY_REDIRECT_URL=http://localhost:8080/oauth2/callback \
    --env OAUTH2_PROXY_COOKIE_SECRET=__24_BYTE_COOKIE_SECRET_ \
    quay.io/oauth2-proxy/oauth2-proxy:latest \
    --alpha-config=/tmp/oauth2-proxy-alpha-config.yaml
