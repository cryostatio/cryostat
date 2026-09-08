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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** A zero expire-after-access duration disables caching, just as a zero maximum size does. */
@QuarkusTest
@TestProfile(SsarClientCacheDisabledByTtlTest.Profile.class)
class SsarClientCacheDisabledByTtlTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cryostat.security.rbac.cache.expire-after-access", "0s");
        }
    }

    @InjectMock SsarClientFactory clientFactory;

    @Inject SsarClientCache cache;

    @Test
    void testEachCallCreatesAndClosesANewClient() {
        KubernetesClient first = mock(KubernetesClient.class);
        KubernetesClient second = mock(KubernetesClient.class);
        when(clientFactory.createClientForToken("tok")).thenReturn(first, second);

        assertSame(
                first,
                cache.withClient(
                        "tok",
                        client -> {
                            verify(client, never()).close();
                            return client;
                        }));
        assertSame(second, cache.withClient("tok", c -> c));

        verify(clientFactory, times(2)).createClientForToken("tok");
        verify(first).close();
        verify(second).close();
    }
}
