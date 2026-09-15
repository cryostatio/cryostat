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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AgentProxyDisabledTest.NoTrustedHostProfile.class)
class AgentProxyDisabledTest {

    public static class NoTrustedHostProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "OPENSHIFT",
                    "cryostat.security.rbac.default-permission", "pods/exec:create");
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testAgentProxyHeaderIgnoredWhenTrustedHostNotConfigured() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        var remoteAddr = mock(io.vertx.core.net.SocketAddress.class);
        var headers = io.vertx.core.http.impl.headers.HeadersMultiMap.headers();
        headers.add(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY, "true");
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER)).thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY)).thenReturn("true");
        when(req.headers()).thenReturn(headers);
        when(req.remoteAddress()).thenReturn(remoteAddr);
        when(remoteAddr.host()).thenReturn("127.0.0.1");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }
}
