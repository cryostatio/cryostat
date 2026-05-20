#!/bin/bash
# Validates that hibernate-jfr version matches hibernate-core version from Quarkus BOM
# This script is automatically run during the Maven validate phase

set -e

HIBERNATE_JFR_VERSION="$1"

# Skip validation if running in quiet mode with DforceStdout (used by CI for property extraction)
if ps -o args= $PPID | grep -q "DforceStdout"; then
    exit 0
fi

if [ -z "$HIBERNATE_JFR_VERSION" ]; then
    echo "ERROR: hibernate-jfr version not provided" >&2
    exit 1
fi

# Get the hibernate-core version from the effective POM
DIR="$(dirname "$(readlink -f "$0")")"
HIBERNATE_CORE_VERSION=$("${DIR}/mvnw" help:evaluate -Dexpression=project.dependencyManagement.dependencies -DforceStdout -q 2>/dev/null | grep -A 2 "hibernate-core" | grep "<version>" | head -1 | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

if [ -z "$HIBERNATE_CORE_VERSION" ]; then
    echo "ERROR: Could not determine hibernate-core version from dependency management" >&2
    exit 1
fi

echo "hibernate-jfr version: $HIBERNATE_JFR_VERSION" >&2
echo "hibernate-core version: $HIBERNATE_CORE_VERSION" >&2

if [ "$HIBERNATE_JFR_VERSION" != "$HIBERNATE_CORE_VERSION" ]; then
    echo "" >&2
    echo "ERROR: hibernate-jfr version ($HIBERNATE_JFR_VERSION) does not match hibernate-core version ($HIBERNATE_CORE_VERSION) from Quarkus BOM" >&2
    echo "Update org.hibernate.orm.hibernate.jfr.version property in pom.xml to $HIBERNATE_CORE_VERSION" >&2
    exit 1
fi

echo "✓ Hibernate versions are aligned" >&2
exit 0
