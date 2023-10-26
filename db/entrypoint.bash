#!/usr/bin/env bash

if [ "${DEBUG}" = "true" ]; then
    set -xEeo pipefail
fi

if [ "$1" = "postgres" ]; then
    shift
fi

exec /usr/local/bin/postgres-entrypoint.sh postgres -c encrypt.key="${PG_ENCRYPT_KEY?:\$PG_ENCRYPT_KEY must be set and non-empty}" "$@"
