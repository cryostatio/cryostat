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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SsarDecisionCacheTest {

    @Inject SsarDecisionCache cache;

    @BeforeEach
    void setUp() {
        cache.invalidateAll();
    }

    @Test
    void testLoaderCalledOnMiss() {
        AtomicInteger calls = new AtomicInteger();
        boolean result =
                cache.get(
                        "token",
                        "pods",
                        "",
                        "get",
                        key -> {
                            calls.incrementAndGet();
                            return true;
                        });
        assertTrue(result);
        assertTrue(calls.get() == 1);
    }

    @Test
    void testLoaderNotCalledOnHit() {
        cache.get("token", "pods", "", "get", key -> true);

        AtomicInteger calls = new AtomicInteger();
        boolean result =
                cache.get(
                        "token",
                        "pods",
                        "",
                        "get",
                        key -> {
                            calls.incrementAndGet();
                            return false;
                        });
        assertTrue(result);
        assertTrue(calls.get() == 0);
    }

    @Test
    void testDenialDecisionCached() {
        cache.get("token", "pods", "exec", "create", key -> false);
        AtomicInteger calls = new AtomicInteger();
        boolean result =
                cache.get(
                        "token",
                        "pods",
                        "exec",
                        "create",
                        key -> {
                            calls.incrementAndGet();
                            return true;
                        });
        assertFalse(result);
        assertTrue(calls.get() == 0);
    }

    @Test
    void testDifferentTokensHaveIndependentEntries() {
        boolean a = cache.get("token-a", "pods", "", "get", key -> true);
        boolean b = cache.get("token-b", "pods", "", "get", key -> false);
        assertTrue(a);
        assertFalse(b);
    }

    @Test
    void testDifferentVerbsHaveIndependentEntries() {
        boolean get = cache.get("token", "pods", "", "get", key -> true);
        boolean create = cache.get("token", "pods", "", "create", key -> false);
        assertTrue(get);
        assertFalse(create);
    }

    @Test
    void testDifferentSubresourcesHaveIndependentEntries() {
        boolean root = cache.get("token", "pods", "", "create", key -> true);
        boolean exec = cache.get("token", "pods", "exec", "create", key -> false);
        assertTrue(root);
        assertFalse(exec);
    }

    @Test
    void testInvalidateAllClearsEntries() {
        cache.get("token", "pods", "", "get", key -> true);
        cache.invalidateAll();
        AtomicInteger calls = new AtomicInteger();
        boolean result =
                cache.get(
                        "token",
                        "pods",
                        "",
                        "get",
                        key -> {
                            calls.incrementAndGet();
                            return false;
                        });
        assertFalse(result);
        assertTrue(calls.get() == 1);
    }
}
