/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.targets;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.management.remote.JMXServiceURL;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.targets.Target.EventKind;
import io.cryostat.targets.Target.TargetDiscovery;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TargetConnectionManager {

    private final JFRConnectionToolkit jfrConnectionToolkit;
    private final AgentConnectionFactory agentConnectionFactory;
    private final Executor executor;
    private final Logger logger;

    private final AsyncLoadingCache<Target, JFRConnection> connections;
    private final Map<Target, Object> targetLocks;
    private final Optional<Semaphore> semaphore;

    @Inject
    TargetConnectionManager(
            JFRConnectionToolkit jfrConnectionToolkit,
            AgentConnectionFactory agentConnectionFactory,
            Executor executor,
            Logger logger) {
        this.jfrConnectionToolkit = jfrConnectionToolkit;
        this.agentConnectionFactory = agentConnectionFactory;
        this.executor = executor;

        int maxTargetConnections = 0; // TODO make configurable

        this.targetLocks = new ConcurrentHashMap<>();
        if (maxTargetConnections > 0) {
            this.semaphore = Optional.of(new Semaphore(maxTargetConnections, true));
        } else {
            this.semaphore = Optional.empty();
        }

        Caffeine<Target, JFRConnection> cacheBuilder =
                Caffeine.newBuilder()
                        .executor(executor)
                        .scheduler(Scheduler.systemScheduler())
                        .removalListener(this::closeConnection);
        Duration ttl = Duration.ofSeconds(10); // TODO make configurable
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "TTL must be a positive integer in seconds, was " + ttl.toSeconds());
        } else {
            cacheBuilder = cacheBuilder.expireAfterAccess(ttl);
        }
        this.connections = cacheBuilder.buildAsync(new ConnectionLoader());
        this.logger = logger;
    }

    @ConsumeEvent(Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) {
        // force removal of connections from cache when we're notified about targets being lost.
        // This should already be taken care of by the connection close listener, but this provides
        // some additional insurance in case a target disappears and the underlying JMX network
        // connection doesn't immediately report itself as closed
        if (EventKind.LOST.equals(event.kind())) {
            for (Target target : connections.asMap().keySet()) {
                if (Objects.equals(target.id, event.serviceRef().id)) {
                    connections.synchronous().invalidate(target);
                }
            }
        }
    }

    public <T> Uni<T> executeConnectedTaskAsync(Target target, ConnectedTask<T> task) {
        synchronized (targetLocks.computeIfAbsent(target, k -> new Object())) {
            return Uni.createFrom()
                    .completionStage(
                            connections
                                    .get(target)
                                    .thenApplyAsync(
                                            conn -> {
                                                try {
                                                    return task.execute(conn);
                                                } catch (Exception e) {
                                                    logger.error("Connection failure", e);
                                                    throw new CompletionException(e);
                                                }
                                            },
                                            executor));
        }
    }

    public <T> T executeConnectedTask(Target target, ConnectedTask<T> task) throws Exception {
        synchronized (targetLocks.computeIfAbsent(target, k -> new Object())) {
            return task.execute(connections.get(target).get());
        }
    }

    /**
     * Mark a connection as still in use by the consumer. Connections expire from cache and are
     * automatically closed after {@link NetworkModule.TARGET_CACHE_TTL}. For long-running
     * operations which may hold the connection open and active for longer than the configured TTL,
     * this method provides a way for the consumer to inform the {@link TargetConnectionManager} and
     * its internal cache that the connection is in fact still active and should not be
     * expired/closed. This will extend the lifetime of the cache entry by another TTL into the
     * future from the time this method is called. This may be done repeatedly as long as the
     * connection is required to remain active.
     *
     * @return false if the connection for the specified {@link Target} was already removed from
     *     cache, true if it is still active and was refreshed
     */
    public boolean markConnectionInUse(Target target) {
        return connections.getIfPresent(target) != null;
    }

    private void closeConnection(Target target, JFRConnection connection, RemovalCause cause) {
        if (target == null) {
            logger.error("Connection eviction triggered with null target");
            return;
        }
        if (connection == null) {
            logger.error("Connection eviction triggered with null connection");
            return;
        }
        try {
            TargetConnectionClosed evt =
                    new TargetConnectionClosed(target.connectUrl, cause.name());
            logger.infov("Removing cached connection for {0}: {1}", target.connectUrl, cause);
            evt.begin();
            try {
                connection.close();
                targetLocks.remove(target);
            } catch (RuntimeException e) {
                evt.setExceptionThrown(true);
                throw e;
            } finally {
                evt.end();
                if (evt.shouldCommit()) {
                    evt.commit();
                }
            }
        } catch (Exception e) {
            logger.error("Connection eviction failed", e);
        } finally {
            if (semaphore.isPresent()) {
                semaphore.get().release();
                logger.tracev(
                        "Semaphore released! Permits: {0}", semaphore.get().availablePermits());
            }
        }
    }

    private JFRConnection connect(Target target) throws Exception {
        var uri = target.connectUrl;
        TargetConnectionOpened evt = new TargetConnectionOpened(uri.toString());
        evt.begin();
        try {
            if (semaphore.isPresent()) {
                semaphore.get().acquire();
            }

            if (target.isAgent()) {
                return agentConnectionFactory.createConnection(uri);
            }
            return jfrConnectionToolkit.connect(
                    new JMXServiceURL(uri.toString()),
                    null /* TODO get from credentials storage */,
                    Collections.singletonList(
                            () -> {
                                this.connections.synchronous().invalidate(target);
                            }));
        } catch (Exception e) {
            evt.setExceptionThrown(true);
            if (semaphore.isPresent()) {
                semaphore.get().release();
            }
            throw e;
        } finally {
            evt.end();
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }
    }

    private class ConnectionLoader implements AsyncCacheLoader<Target, JFRConnection> {

        @Override
        public CompletableFuture<JFRConnection> asyncLoad(Target key, Executor executor)
                throws Exception {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            logger.infov("Opening connection to {0}", key.connectUrl);
                            return connect(key);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    },
                    executor);
        }

        @Override
        public CompletableFuture<JFRConnection> asyncReload(
                Target key, JFRConnection prev, Executor executor) throws Exception {
            // if we're refreshed and already have an existing, open connection, just reuse it.
            if (prev.isConnected()) {
                logger.infov("Reusing connection to {0}", key.connectUrl);
                return CompletableFuture.completedFuture(prev);
            }
            logger.infov("Refreshing connection to {0}", key.connectUrl);
            return asyncLoad(key, executor);
        }
    }

    public interface ConnectedTask<T> {
        T execute(JFRConnection connection) throws Exception;
    }

    @Name("io.cryostat.net.TargetConnectionManager.TargetConnectionOpened")
    @Label("Target Connection Status")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class TargetConnectionOpened extends Event {
        String serviceUri;
        boolean exceptionThrown;

        TargetConnectionOpened(String serviceUri) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }

    @Name("io.cryostat.net.TargetConnectionManager.TargetConnectionClosed")
    @Label("Target Connection Status")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class TargetConnectionClosed extends Event {
        URI serviceUri;
        boolean exceptionThrown;
        String reason;

        TargetConnectionClosed(URI serviceUri, String reason) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
            this.reason = reason;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }
}
