#!/bin/bash

createBucket() {
    awslocal s3 mb "s3://${1}"
}

createBuckets() {
    for name in "$@"; do
        createBucket "${name}"
    done
}

names=()
for i in $(echo "$CRYOSTAT_BUCKETS" | tr ',' '\n'); do
    len="${#i}"
    if [ "${len}" -lt 3 ]; then
        echo "Bucket names must be at least 3 characters"
        exit 1
    fi
    if [ "${len}" -gt 63 ]; then
        echo "Bucket names must be at most 63 characters"
        exit 1
    fi
    names+=("${i}")
done

createBuckets "${names[@]}"
