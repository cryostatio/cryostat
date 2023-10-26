#!/usr/bin/env bash

if [ "${DEBUG}" = "true" ]; then
    set -xEeo pipefail
fi

if [ -z "${PG_ENCRYPT_KEY}" ]; then
    echo "\$PG_ENCRYPT_KEY must be set and non-empty."
    exit 1
fi

if [ "$1" = "postgres" ]; then
    shift
fi

exec /usr/local/bin/postgres-entrypoint.sh postgres -c encrypt.key="${PG_ENCRYPT_KEY}" "$@"
