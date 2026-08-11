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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SsarClientCacheTest {

    @InjectMock SsarClientFactory clientFactory;

    @Inject SsarClientCache cache;

    @BeforeEach
    void setUp() {
        cache.shutdown();
    }

    @Test
    void testGetOrCreateReturnsCachedClientForSameToken() {
        KubernetesClient mockClient = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken(anyString())).thenReturn(mockClient);

        KubernetesClient first = cache.getOrCreate("token-abc");
        KubernetesClient second = cache.getOrCreate("token-abc");

        assertSame(first, second);
        verify(clientFactory, times(1)).createClientForToken("token-abc");
    }

    @Test
    void testGetOrCreateReturnsDifferentClientsForDifferentTokens() {
        KubernetesClient clientA = mock(KubernetesClient.class);
        KubernetesClient clientB = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken("token-A")).thenReturn(clientA);
        when(clientFactory.createClientForToken("token-B")).thenReturn(clientB);

        KubernetesClient gotA = cache.getOrCreate("token-A");
        KubernetesClient gotB = cache.getOrCreate("token-B");

        assertNotSame(gotA, gotB);
        assertSame(clientA, gotA);
        assertSame(clientB, gotB);
    }

    @Test
    void testInvalidateClosesClient() {
        KubernetesClient mockClient = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken(anyString())).thenReturn(mockClient);

        cache.getOrCreate("token-close");
        cache.invalidate("token-close");

        verify(mockClient).close();
    }

    @Test
    void testShutdownClosesAllCachedClients() {
        KubernetesClient c1 = mock(KubernetesClient.class);
        KubernetesClient c2 = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken("t1")).thenReturn(c1);
        when(clientFactory.createClientForToken("t2")).thenReturn(c2);

        cache.getOrCreate("t1");
        cache.getOrCreate("t2");
        cache.shutdown();

        verify(c1).close();
        verify(c2).close();
    }

    @Test
    void testGetOrCreateAfterInvalidateCreatesNewClient() {
        KubernetesClient first = mock(KubernetesClient.class);
        KubernetesClient second = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken("tok")).thenReturn(first, second);

        KubernetesClient got1 = cache.getOrCreate("tok");
        cache.invalidate("tok");
        KubernetesClient got2 = cache.getOrCreate("tok");

        assertNotSame(got1, got2);
        verify(clientFactory, times(2)).createClientForToken("tok");
    }

    @Test
    void testNonAsciiTokenIsHashedAndCached() {
        KubernetesClient mockClient = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken(anyString())).thenReturn(mockClient);

        KubernetesClient result = cache.getOrCreate("tëst-ünïcödé-tökën");

        assertNotNull(result);
        assertSame(mockClient, result);
        verify(clientFactory, times(1)).createClientForToken("tëst-ünïcödé-tökën");
    }

    @Test
    void testGetOrCreateReturnsNotNull() {
        KubernetesClient mockClient = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken(anyString())).thenReturn(mockClient);

        assertNotNull(cache.getOrCreate("any-token"));
    }
}
