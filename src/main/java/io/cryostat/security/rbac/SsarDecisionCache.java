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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Short-lived in-memory cache of SelfSubjectAccessReview (SSAR) decisions.
 *
 * <p>Cache key: SHA-256 hex digest of the caller's raw bearer token plus the Kubernetes
 * resource/subresource/verb triple and the config namespace. Raw tokens are never stored.
 *
 * <p>Eviction policy: expire-after-write (configurable, default 1 minute). Reads do not refresh the
 * TTL, so stale decisions are bounded to the configured window regardless of traffic.
 *
 * <p>Use {@link #get(String, String, String, String, Function)} to look up or compute-and-store a
 * decision in a single call.
 */
@ApplicationScoped
public class SsarDecisionCache {

    /**
     * Compound cache key: token hash, Kubernetes resource/subresource/verb, and the config
     * namespace (empty string when cluster-scoped).
     */
    record DecisionKey(
            String tokenHash, String resource, String subresource, String verb, String namespace) {}

    private final Cache<DecisionKey, Boolean> decisionCache;
    private final String namespace;
    private final Logger logger;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "SpotBugs thinks Logger instance is externally modifiable")
    @Inject
    public SsarDecisionCache(Logger logger, RbacConfig config) {
        this.logger = logger;
        this.namespace = config.namespace().orElse("");
        this.decisionCache =
                Caffeine.newBuilder()
                        .expireAfterWrite(config.decisionCache().ttl())
                        .maximumSize(config.decisionCache().maximumSize())
                        .build();
    }

    /**
     * Return the cached SSAR decision, or compute, store, and return it via {@code loader}.
     *
     * <p>The {@code loader} receives the compound {@link DecisionKey} and must return a non-null
     * {@link Boolean}. If the loader throws, the exception propagates to the caller and nothing is
     * stored in the cache.
     *
     * @param rawToken the caller's raw bearer token
     * @param resource Kubernetes resource name
     * @param subresource Kubernetes subresource name (may be empty)
     * @param verb Kubernetes verb
     * @param loader function invoked on a cache miss to compute the decision
     * @return the cached or freshly computed decision
     */
    public boolean get(
            String rawToken,
            String resource,
            String subresource,
            String verb,
            Function<DecisionKey, Boolean> loader) {
        DecisionKey key =
                new DecisionKey(hashToken(rawToken), resource, subresource, verb, namespace);
        Boolean result = decisionCache.get(key, loader);
        logger.debugf(
                "SSAR decision cache: allowed=%b for %s/%s:%s (namespace: %s)",
                result, resource, subresource, verb, namespace.isEmpty() ? "<cluster>" : namespace);
        return Boolean.TRUE.equals(result);
    }

    /** Discard all cached decisions. Useful for testing. */
    public void invalidateAll() {
        decisionCache.invalidateAll();
        decisionCache.cleanUp();
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
