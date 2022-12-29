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

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.management.remote.JMXServiceURL;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.targets.Targets.EventKind;
import io.cryostat.targets.Targets.TargetDiscoveryEvent;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Dependent
public class TargetConnectionManager {

    private final JFRConnectionToolkit jfrConnectionToolkit;
    private final Executor executor;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final AsyncLoadingCache<Target, JFRConnection> connections;
    private final Map<Target, Object> targetLocks;
    private final Optional<Semaphore> semaphore;

    @Inject
    TargetConnectionManager(JFRConnectionToolkit jfrConnectionToolkit) {
        this.jfrConnectionToolkit = jfrConnectionToolkit;
        this.executor = Infrastructure.getDefaultExecutor();

        int maxTargetConnections = 2; // TODO make configurable

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
        Duration ttl = Duration.ofSeconds(10);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "TTL must be a positive integer in seconds, was " + ttl.toSeconds());
        } else {
            cacheBuilder = cacheBuilder.expireAfterAccess(ttl);
        }
        this.connections = cacheBuilder.buildAsync(new ConnectionLoader());
    }

    @ConsumeEvent(Targets.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscoveryEvent event) {
        // force removal of connections from cache when we're notified about targets being lost.
        // This should already be taken care of by the connection close listener, but this provides
        // some additional insurance in case a target disappears and the underlying JMX network
        // connection doesn't immediately report itself as closed
        if (EventKind.LOST.equals(event.event().kind())) {
            for (Target target : connections.asMap().keySet()) {
                if (Objects.equals(target.id, event.event().serviceRef().id)) {
                    connections.synchronous().invalidate(target);
                }
            }
        }
    }

    public <T> CompletableFuture<T> executeConnectedTaskAsync(
            Target target, ConnectedTask<T> task) {
        synchronized (targetLocks.computeIfAbsent(target, k -> new Object())) {
            return connections
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
                            executor);
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
     * @return false if the connection for the specified {@link ConnectionDescriptor} was already
     *     removed from cache, true if it is still active and was refreshed
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
            JMXConnectionClosed evt = new JMXConnectionClosed(target.connectUrl, cause.name());
            logger.info("Removing cached connection for {}: {}", target.connectUrl, cause);
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
                logger.trace("Semaphore released! Permits: {}", semaphore.get().availablePermits());
            }
        }
    }

    private JFRConnection connect(Target target) throws Exception {
        var uri = target.connectUrl;
        JMXConnectionOpened evt = new JMXConnectionOpened(uri.toString());
        evt.begin();
        try {
            if (semaphore.isPresent()) {
                semaphore.get().acquire();
            }
            return jfrConnectionToolkit.connect(
                    new JMXServiceURL(uri.toString()),
                    null /* TODO get from credentials storage */,
                    Collections.singletonList(
                            () -> {
                                logger.info("Connection for {} closed from target side", uri);
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
                            logger.info("Opening connection to {}", key.connectUrl);
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
                logger.info("Reusing connection to {}", key.connectUrl);
                return CompletableFuture.completedFuture(prev);
            }
            logger.info("Refreshing connection to {}", key.connectUrl);
            return asyncLoad(key, executor);
        }
    }

    public interface ConnectedTask<T> {
        T execute(JFRConnection connection) throws Exception;
    }

    @Name("io.cryostat.net.TargetConnectionManager.JMXConnectionOpened")
    @Label("JMX Connection Status")
    @Category("Cryostat")
    @SuppressFBWarnings(
            value = "URF_UNREAD_FIELD",
            justification = "The event fields are recorded with JFR instead of accessed directly")
    public static class JMXConnectionOpened extends Event {
        String serviceUri;
        boolean exceptionThrown;

        JMXConnectionOpened(String serviceUri) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }

    @Name("io.cryostat.net.TargetConnectionManager.JMXConnectionClosed")
    @Label("JMX Connection Status")
    @Category("Cryostat")
    @SuppressFBWarnings(
            value = "URF_UNREAD_FIELD",
            justification = "The event fields are recorded with JFR instead of accessed directly")
    public static class JMXConnectionClosed extends Event {
        URI serviceUri;
        boolean exceptionThrown;
        String reason;

        JMXConnectionClosed(URI serviceUri, String reason) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
            this.reason = reason;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }
}
