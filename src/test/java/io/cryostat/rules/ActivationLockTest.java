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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the PostgreSQL advisory lock used to serialize rule activation for a jvmId against the
 * real test database.
 */
@QuarkusTest
public class ActivationLockTest {

    @Inject ActivationLock lock;

    ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void returnsActionResult() throws Exception {
        assertEquals("result", lock.withLock("jvm-a", () -> "result"));
    }

    @Test
    void sameKeyIsMutuallyExclusive() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        Future<?> a = pool.submit(() -> holdBriefly(lock, "jvm-same", inside, maxInside));
        Future<?> b = pool.submit(() -> holdBriefly(lock, "jvm-same", inside, maxInside));
        a.get(20, TimeUnit.SECONDS);
        b.get(20, TimeUnit.SECONDS);

        assertEquals(1, maxInside.get(), "the same key must never be held by two callers at once");
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        Future<Boolean> a = pool.submit(() -> awaitPeer(bothInside, "jvm-one"));
        Future<Boolean> b = pool.submit(() -> awaitPeer(bothInside, "jvm-two"));

        assertTrue(a.get(20, TimeUnit.SECONDS), "distinct keys must be held concurrently");
        assertTrue(b.get(20, TimeUnit.SECONDS), "distinct keys must be held concurrently");
    }

    @Test
    void waitIsBoundedWhenLockIsHeldElsewhere() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder =
                pool.submit(
                        () ->
                                lock.withLock(
                                        "jvm-busy",
                                        () -> {
                                            held.countDown();
                                            return release.await(20, TimeUnit.SECONDS);
                                        }));
        assertTrue(held.await(20, TimeUnit.SECONDS));

        long start = System.nanoTime();
        assertThrows(
                Exception.class,
                () -> lock.withLock("jvm-busy", Duration.ofMillis(500), () -> "unreachable"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        assertTrue(
                elapsedMillis < 10_000,
                "waiting for a held lock must give up after the configured wait, took "
                        + elapsedMillis
                        + "ms");
    }

    @Test
    void lockIsReleasedWhenActionFails() throws Exception {
        ExecutionException ee =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                pool.submit(
                                                () ->
                                                        lock.withLock(
                                                                "jvm-fail",
                                                                () -> {
                                                                    throw new IllegalStateException(
                                                                            "boom");
                                                                }))
                                        .get(20, TimeUnit.SECONDS));
        assertTrue(ee.getCause() instanceof IllegalStateException);

        assertEquals(
                "ok",
                lock.withLock("jvm-fail", Duration.ofSeconds(2), () -> "ok"),
                "a failed action must not leave the key locked");
    }

    private static Boolean holdBriefly(
            ActivationLock lock, String key, AtomicInteger inside, AtomicInteger maxInside)
            throws Exception {
        return lock.withLock(
                key,
                () -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(500);
                    } finally {
                        inside.decrementAndGet();
                    }
                    return true;
                });
    }

    private Boolean awaitPeer(CountDownLatch bothInside, String key) throws Exception {
        return lock.withLock(
                key,
                () -> {
                    bothInside.countDown();
                    return bothInside.await(10, TimeUnit.SECONDS);
                });
    }
}
