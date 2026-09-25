/*
 * Validates that the hibernate-jfr version matches the resolved hibernate-core
 * version. The resolved version is read from the output of dependency:list,
 * which runs within the Maven session and handles all repository settings.
 */

def resolvedFile = new File(project.build.directory, "hibernate-core-resolved.txt")
if (!resolvedFile.exists()) {
    throw new RuntimeException(
        "hibernate-core-resolved.txt not found. " +
        "Ensure the maven-dependency-plugin list goal runs before this script.")
}

def coreVersion = null
resolvedFile.eachLine { line ->
    // dependency:list output format: org.hibernate.orm:hibernate-core:jar:VERSION:SCOPE
    def matcher = (line =~ /hibernate-core:jar:([^:]+)/)
    if (matcher.find()) {
        coreVersion = matcher.group(1)
    }
}

if (!coreVersion) {
    throw new RuntimeException(
        "Could not determine hibernate-core version from resolved dependencies")
}

def jfrVersion = project.properties['org.hibernate.orm.hibernate.jfr.version']
if (!jfrVersion) {
    throw new RuntimeException(
        "org.hibernate.orm.hibernate.jfr.version property is not set in pom.xml")
}

// Strip build qualifiers for comparison
def normalizeVersion = { v -> v.contains('-') ? v.substring(0, v.indexOf('-')) : v }
def jfrBase = normalizeVersion(jfrVersion)
def coreBase = normalizeVersion(coreVersion)

log.info("hibernate-jfr version: ${jfrVersion} (base: ${jfrBase})")
log.info("hibernate-core version: ${coreVersion} (base: ${coreBase})")

if (jfrBase != coreBase) {
    throw new RuntimeException(
        "hibernate-jfr base version (${jfrBase}) does not match " +
        "hibernate-core base version (${coreBase}) from resolved dependencies. " +
        "Update org.hibernate.orm.hibernate.jfr.version in pom.xml to match ${coreVersion}")
}

log.info("\u2713 Hibernate versions are aligned")
