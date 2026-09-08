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
import com.github.benmanes.caffeine.cache.RemovalCause;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Recency-based in-memory cache of Fabric8 {@link KubernetesClient} instances keyed by a SHA-256
 * hash of the caller's bearer token.
 *
 * <ul>
 *   <li>Eviction policy: expire-after-access (configurable, default 5 minutes).
 *   <li>Size cap: configurable maximum number of entries (default 1000).
 *   <li>Resource safety: evicted clients are automatically closed.
 *   <li>Security: raw tokens are never stored; only their SHA-256 hex digest is used as the key.
 * </ul>
 *
 * <p>Setting either the expire-after-access duration or the maximum size to zero disables caching
 * entirely: {@link #withClient(String, Function)} then creates a fresh client per call and closes
 * it as soon as the caller is done with it.
 */
@ApplicationScoped
public class SsarClientCache {

    // null when caching is disabled by configuration
    private final Cache<String, KubernetesClient> clientCache;
    private final SsarClientFactory clientFactory;
    private final Logger logger;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "SpotBugs thinks Logger instance is externally modifiable")
    @Inject
    public SsarClientCache(Logger logger, RbacConfig config, SsarClientFactory clientFactory) {
        this.logger = logger;
        this.clientFactory = clientFactory;
        var expireAfterAccess = config.cache().expireAfterAccess();
        long maximumSize = config.cache().maximumSize();
        // negative values are rejected by Caffeine below, failing startup as documented
        if (expireAfterAccess.isZero() || maximumSize == 0) {
            logger.debugf(
                    "SsarClientCache disabled (expire-after-access=%s, maximum-size=%d)",
                    expireAfterAccess, maximumSize);
            this.clientCache = null;
            return;
        }
        this.clientCache =
                Caffeine.newBuilder()
                        .expireAfterAccess(expireAfterAccess)
                        .maximumSize(maximumSize)
                        .removalListener(
                                (String keyHash, KubernetesClient client, RemovalCause cause) -> {
                                    if (client != null
                                            && cause != RemovalCause.EXPLICIT
                                            && cause != RemovalCause.REPLACED) {
                                        logger.debugf(
                                                "Closing cached KubernetesClient for token hash"
                                                        + " %s due to %s",
                                                keyHash, cause);
                                        try {
                                            client.close();
                                        } catch (Exception e) {
                                            logger.warnf(
                                                    e,
                                                    "Error closing KubernetesClient for token"
                                                            + " hash %s",
                                                    keyHash);
                                        }
                                    }
                                })
                        .build();
    }

    /**
     * Apply {@code fn} to a client authenticated with the given raw token. When caching is enabled
     * the client is cached and remains open after this call returns; when caching is disabled a
     * fresh client is created and closed before returning. Callers must not retain the client
     * beyond the scope of {@code fn}.
     */
    public <T> T withClient(String rawToken, Function<KubernetesClient, T> fn) {
        if (clientCache == null) {
            try (KubernetesClient client = clientFactory.createClientForToken(rawToken)) {
                return fn.apply(client);
            }
        }
        return fn.apply(getOrCreate(rawToken));
    }

    /**
     * Return a cached client for the given raw token, creating one via {@link SsarClientFactory} if
     * none exists.
     */
    private KubernetesClient getOrCreate(String rawToken) {
        String keyHash = hashToken(rawToken);
        return clientCache.get(keyHash, hash -> clientFactory.createClientForToken(rawToken));
    }

    /** Evict and close the cached client for the given raw token, if one exists. */
    public void invalidate(String rawToken) {
        if (clientCache == null) {
            return;
        }
        String keyHash = hashToken(rawToken);
        KubernetesClient client = clientCache.getIfPresent(keyHash);
        clientCache.invalidate(keyHash);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warnf(e, "Error closing KubernetesClient for token hash %s", keyHash);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (clientCache == null) {
            return;
        }
        logger.info(
                "Shutting down SsarClientCache and closing all cached KubernetesClient instances");
        clientCache
                .asMap()
                .values()
                .forEach(
                        client -> {
                            try {
                                client.close();
                            } catch (Exception e) {
                                logger.warnf(e, "Error closing KubernetesClient during shutdown");
                            }
                        });
        clientCache.invalidateAll();
        clientCache.cleanUp();
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
