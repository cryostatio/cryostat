#!/usr/bin/env bash

if [ "$1" = "postgres" ]; then
    shift
fi

exec /usr/bin/run-postgresql \
    -c encrypt.key="${PG_ENCRYPT_KEY?:\$PG_ENCRYPT_KEY must be set and non-empty}" \
    "$@"
