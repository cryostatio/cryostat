/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.security.rbac;

import io.cryostat.targets.Target;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.jboss.logging.Logger;

/**
 * Resolves the Kubernetes namespace for a given jvmId using the following fallback chain:
 *
 * <ol>
 *   <li><b>Live lookup</b> — {@link Target#getTargetByJvmId(String)} followed by {@link
 *       Target#getNamespace()}.
 *   <li><b>Envers fallback</b> — queries the {@code Target_AUD} audit table for the most recent
 *       revision whose {@code jvmId} matches, then reads the namespace from the audited {@link
 *       io.cryostat.discovery.DiscoveryNode}.
 *   <li><b>Installation namespace</b> — the value of {@code cryostat.security.rbac.namespace}, or
 *       the empty string when that property is unset (cluster-scoped).
 * </ol>
 *
 * <p>All methods return a non-null string. If jvmId is null or blank, steps 1 and 2 are skipped and
 * the installation namespace is returned immediately.
 */
@ApplicationScoped
public class SecurityContextResolver {

    @Inject RbacConfig config;
    @Inject EntityManager em;
    @Inject Logger logger;

    /**
     * Resolves the namespace for the given {@code jvmId} using the three-step fallback chain
     * described in the class Javadoc.
     *
     * @param jvmId the JVM instance fingerprint; may be null or blank
     * @return the resolved namespace, never null (falls back to installation namespace or empty
     *     string)
     */
    public String resolveNamespace(String jvmId) {
        if (StringUtils.isBlank(jvmId)) {
            return installationNamespace();
        }

        // Step 1: live lookup
        var liveTarget = Target.getTargetByJvmId(jvmId);
        if (liveTarget.isPresent()) {
            var ns = liveTarget.get().getNamespace();
            if (ns.isPresent()) {
                logger.debugf(
                        "SecurityContextResolver: namespace '%s' from live target for jvmId '%s'",
                        ns.get(), jvmId);
                return ns.get();
            }
        }

        // Step 2: Envers audit fallback (for deleted targets)
        try {
            var ar = AuditReaderFactory.get(em);
            var results =
                    ar.createQuery()
                            .forRevisionsOfEntity(Target.class, true, false)
                            .add(AuditEntity.property("jvmId").eq(jvmId))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (!results.isEmpty()) {
                var auditedTarget = (Target) results.get(0);
                var ns = auditedTarget.getNamespace();
                if (ns.isPresent()) {
                    logger.debugf(
                            "SecurityContextResolver: namespace '%s' from Envers audit for jvmId"
                                    + " '%s'",
                            ns.get(), jvmId);
                    return ns.get();
                }
            }
        } catch (IllegalStateException e) {
            logger.debug(
                    "SecurityContextResolver: Envers not available, falling back to installation"
                            + " namespace",
                    e);
        }

        // Step 3: installation namespace fallback
        String fallback = installationNamespace();
        logger.debugf(
                "SecurityContextResolver: using installation namespace '%s' for jvmId '%s'",
                fallback.isEmpty() ? "<cluster-scoped>" : fallback, jvmId);
        return fallback;
    }

    /**
     * Returns the installation namespace directly. Used for server-scoped resources that have no
     * target association (Category 4 endpoints).
     *
     * @return the configured installation namespace, or empty string when cluster-scoped
     */
    public String resolveNamespace() {
        return installationNamespace();
    }

    private String installationNamespace() {
        return config.namespace().orElse("");
    }
}
