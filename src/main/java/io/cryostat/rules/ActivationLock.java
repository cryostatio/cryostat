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
package io.cryostat.rules;

import java.time.Duration;
import java.util.concurrent.Callable;

import io.cryostat.ConfigProperties;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mutual exclusion for the short critical sections of Automated Rule activation that must not
 * interleave for the same JVM. The same physical JVM can be represented by several Targets which
 * share a jvmId, and rule activations for each of them operate on the same Quartz archival job.
 *
 * <p>The lock is a PostgreSQL transaction-scoped advisory lock, so it is held in the database
 * rather than in this process and is released by the database when the lock's transaction ends,
 * whether it commits, rolls back, or the connection is lost. The action runs inside that
 * transaction, so the lock is held for exactly as long as the action.
 */
@ApplicationScoped
public class ActivationLock {

    private static final String KEY_PREFIX = "rule-activation:";

    @Inject EntityManager em;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration defaultWait;

    /**
     * Run the action while holding the lock for the given key, waiting at most the configured
     * connection timeout to acquire it.
     *
     * @see #withLock(String, Duration, Callable)
     */
    public <T> T withLock(String key, Callable<T> action) throws Exception {
        return withLock(key, defaultWait, action);
    }

    /**
     * Run the action while holding the lock for the given key.
     *
     * @param key identifies the resource being protected, ex. a jvmId
     * @param wait the longest to wait to acquire the lock before failing
     * @param action work to perform while the lock is held. It runs in a new transaction that is
     *     independent of any caller transaction, so its effects are committed when it returns.
     */
    public <T> T withLock(String key, Duration wait, Callable<T> action) throws Exception {
        return QuarkusTransaction.requiringNew()
                .call(
                        () -> {
                            em.createNativeQuery("SELECT set_config('lock_timeout', :ms, true)")
                                    .setParameter("ms", wait.toMillis() + "ms")
                                    .getSingleResult();
                            em.createNativeQuery(
                                            "SELECT CAST(pg_advisory_xact_lock("
                                                    + "hashtextextended(:key, 0)) AS text)")
                                    .setParameter("key", KEY_PREFIX + key)
                                    .getSingleResult();
                            em.createNativeQuery("SELECT set_config('lock_timeout', '0', true)")
                                    .getSingleResult();
                            return action.call();
                        });
    }
}
