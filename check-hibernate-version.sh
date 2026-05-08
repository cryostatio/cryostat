#!/bin/bash
# Validates that hibernate-jfr version matches hibernate-core version from Quarkus BOM
# This script is automatically run during the Maven validate phase

set -e

HIBERNATE_JFR_VERSION="$1"

if [ -z "$HIBERNATE_JFR_VERSION" ]; then
    echo "ERROR: hibernate-jfr version not provided"
    exit 1
fi

# Get the hibernate-core version from the effective POM
HIBERNATE_CORE_VERSION=$(mvn help:evaluate -Dexpression=project.dependencyManagement.dependencies -DforceStdout -q 2>/dev/null | grep -A 2 "hibernate-core" | grep "<version>" | head -1 | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

if [ -z "$HIBERNATE_CORE_VERSION" ]; then
    echo "ERROR: Could not determine hibernate-core version from dependency management"
    exit 1
fi

echo "hibernate-jfr version: $HIBERNATE_JFR_VERSION"
echo "hibernate-core version: $HIBERNATE_CORE_VERSION"

if [ "$HIBERNATE_JFR_VERSION" != "$HIBERNATE_CORE_VERSION" ]; then
    echo ""
    echo "ERROR: hibernate-jfr version ($HIBERNATE_JFR_VERSION) does not match hibernate-core version ($HIBERNATE_CORE_VERSION) from Quarkus BOM"
    echo "Update org.hibernate.orm.hibernate.jfr.version property in pom.xml to $HIBERNATE_CORE_VERSION"
    exit 1
fi

echo "✓ Hibernate versions are aligned"
exit 0