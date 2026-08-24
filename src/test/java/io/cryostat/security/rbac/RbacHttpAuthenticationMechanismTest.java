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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Permission;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@TestProfile(RbacHttpAuthenticationMechanismTest.Profile.class)
class RbacHttpAuthenticationMechanismTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "OPENSHIFT",
                    "cryostat.security.rbac.default-permission", "pods/exec:create",
                    "cryostat.http.proxy.mtls.trusted-hosts", "127.0.0.1",
                    "quarkus.http.proxy.trusted-proxies", "127.0.0.1");
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @InjectMock SsarClientCache ssarClientCache;
    @InjectMock SsarDecisionCache ssarDecisionCache;

    private KubernetesClient mockClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockClient = mock(KubernetesClient.class);
        when(ssarClientCache.getOrCreate(any())).thenReturn(mockClient);
        when(ssarDecisionCache.get(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            java.util.function.Function<SsarDecisionCache.DecisionKey, Boolean>
                                    loader = invocation.getArgument(4);
                            return loader != null ? loader.apply(null) : false;
                        });

        var authGroup = mock(AuthorizationAPIGroupDSL.class);
        var v1auth = mock(V1AuthorizationAPIGroupDSL.class);
        var ssarOp = mock(InOutCreateable.class);

        when(mockClient.authorization()).thenReturn(authGroup);
        when(authGroup.v1()).thenReturn(v1auth);
        when(v1auth.selfSubjectAccessReview()).thenReturn(ssarOp);
    }

    @Test
    void testAuthenticateReturnsIdentityWhenBothHeadersPresent() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }

    @Test
    void testAuthenticateReturnsNullWhenUserHeaderMissing() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER)).thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testAuthenticateReturnsNullWhenTokenHeaderMissing() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn(null);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testPermissionCheckerGrantsWhenSsarAllowed() {
        var status = new SubjectAccessReviewStatus();
        status.setAllowed(true);
        var review = new SelfSubjectAccessReview();
        review.setStatus(status);
        when(((InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview>)
                                mockClient.authorization().v1().selfSubjectAccessReview())
                        .create(any()))
                .thenReturn(review);

        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();
        assertNotNull(identity);

        // Quarkus @PermissionsAllowed("credentials:read") → StringPermission("credentials","read")
        boolean allowed =
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely();
        assertTrue(allowed);
    }

    @Test
    void testPermissionCheckerDenieswhenSsarDenied() {
        var status = new SubjectAccessReviewStatus();
        status.setAllowed(false);
        var review = new SelfSubjectAccessReview();
        review.setStatus(status);
        when(((InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview>)
                                mockClient.authorization().v1().selfSubjectAccessReview())
                        .create(any()))
                .thenReturn(review);

        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();
        assertNotNull(identity);

        boolean allowed =
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely();
        assertFalse(allowed);
    }

    @Test
    void testPermissionCheckerSplitsCommaSeparatedActions() {
        var allowStatus = new SubjectAccessReviewStatus();
        allowStatus.setAllowed(true);
        var allowReview = new SelfSubjectAccessReview();
        allowReview.setStatus(allowStatus);

        var ssarOp =
                (InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview>)
                        mockClient.authorization().v1().selfSubjectAccessReview();
        var createCount = new AtomicInteger();
        when(ssarOp.create(any()))
                .thenAnswer(
                        invocation -> {
                            createCount.incrementAndGet();
                            return allowReview;
                        });

        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();
        assertNotNull(identity);

        Permission permission = new CommaSeparatedActionPermission("targets", "read,write");

        boolean allowed = identity.checkPermission(permission).await().indefinitely();
        assertTrue(allowed);
        assertTrue(createCount.get() == 2);
    }

    @Test
    void testPermissionCheckerUsesCachedDecisionWithoutSsar() {
        when(ssarDecisionCache.get(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> true);

        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();
        assertNotNull(identity);

        boolean allowed =
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely();
        assertTrue(allowed);

        var ssarOp =
                (InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview>)
                        mockClient.authorization().v1().selfSubjectAccessReview();
        Mockito.verify(ssarOp, Mockito.never()).create(any());
    }

    @Test
    void testAuthenticateUsesAuthorizationBearerWhenForwardedTokenMissing() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AUTHORIZATION))
                .thenReturn("Bearer my-bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }

    @Test
    void testAuthenticatePrefersForwardedTokenOverAuthorizationBearer() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("forwarded-token");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AUTHORIZATION))
                .thenReturn("Bearer bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }

    @Test
    void testAuthenticateReturnsNullWhenBothTokenSourcesMissing() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AUTHORIZATION)).thenReturn(null);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testAuthenticateIgnoresNonBearerAuthorizationHeader() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn(null);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AUTHORIZATION))
                .thenReturn("Basic dXNlcjpwYXNz");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testAgentProxyHeaderGrantsPermissiveIdentityWhenNoForwardedUser() {
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

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("cryostat-agent"));
        assertTrue(
                identity.checkPermission(new StringPermission("discoverynodes", "write"))
                        .await()
                        .indefinitely());
    }

    @Test
    void testAgentProxyHeaderStrippedWhenForwardedUserPresent() {
        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        var remoteAddr = mock(io.vertx.core.net.SocketAddress.class);
        var headers = io.vertx.core.http.impl.headers.HeadersMultiMap.headers();
        headers.add(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY, "true");
        headers.add(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin");
        headers.add(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("my-token");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY)).thenReturn("true");
        when(req.headers()).thenReturn(headers);
        when(req.remoteAddress()).thenReturn(remoteAddr);
        when(remoteAddr.host()).thenReturn("127.0.0.1");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertTrue(identity.getPrincipal().getName().equals("admin"));
        assertFalse(headers.contains(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY));
    }

    @Test
    void testAgentProxyHeaderRejectedWhenRemoteAddressNotTrusted() {
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
        when(remoteAddr.host()).thenReturn("10.0.0.99");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testCachedDenialSkipsSsar() {
        when(ssarDecisionCache.get(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> false);

        var ctx = mock(RoutingContext.class);
        var req = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                .thenReturn("admin");
        when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                .thenReturn("bearer-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();
        assertNotNull(identity);

        boolean allowed =
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely();
        assertFalse(allowed);

        var ssarOp =
                (InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview>)
                        mockClient.authorization().v1().selfSubjectAccessReview();
        Mockito.verify(ssarOp, Mockito.never()).create(any());
    }

    static class CommaSeparatedActionPermission extends Permission {

        private final String actions;

        CommaSeparatedActionPermission(String name, String actions) {
            super(name);
            this.actions = actions;
        }

        @Override
        public boolean implies(Permission permission) {
            return false;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CommaSeparatedActionPermission other)) {
                return false;
            }
            return getName().equals(other.getName()) && actions.equals(other.actions);
        }

        @Override
        public int hashCode() {
            return 31 * getName().hashCode() + actions.hashCode();
        }

        @Override
        public String getActions() {
            return actions;
        }
    }

    @QuarkusTest
    @TestProfile(AgentProxyDisabledTest.NoTrustedHostProfile.class)
    @SuppressWarnings("unused")
    static class AgentProxyDisabledTest {

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
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                    .thenReturn(null);
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                    .thenReturn(null);
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY))
                    .thenReturn("true");
            when(req.headers()).thenReturn(headers);
            when(req.remoteAddress()).thenReturn(remoteAddr);
            when(remoteAddr.host()).thenReturn("127.0.0.1");

            SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

            assertFalse(identity != null && !identity.isAnonymous());
        }
    }

    @QuarkusTest
    @TestProfile(AgentProxyNotInTrustedProxiesTest.MismatchedProfile.class)
    @SuppressWarnings("unused")
    static class AgentProxyNotInTrustedProxiesTest {

        public static class MismatchedProfile implements QuarkusTestProfile {
            @Override
            public Map<String, String> getConfigOverrides() {
                return Map.of(
                        "cryostat.security.rbac.mode", "OPENSHIFT",
                        "cryostat.security.rbac.default-permission", "pods/exec:create",
                        "cryostat.http.proxy.mtls.trusted-hosts", "127.0.0.1",
                        "quarkus.http.proxy.trusted-proxies", "some-other-proxy");
            }
        }

        @Inject RbacHttpAuthenticationMechanism mechanism;

        @Test
        void testAgentProxyHeaderIgnoredWhenNotInTrustedProxies() {
            var ctx = mock(RoutingContext.class);
            var req = mock(io.vertx.core.http.HttpServerRequest.class);
            var remoteAddr = mock(io.vertx.core.net.SocketAddress.class);
            var headers = io.vertx.core.http.impl.headers.HeadersMultiMap.headers();
            headers.add(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY, "true");
            when(ctx.request()).thenReturn(req);
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER))
                    .thenReturn(null);
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN))
                    .thenReturn(null);
            when(req.getHeader(RbacHttpAuthenticationMechanism.HEADER_AGENT_PROXY))
                    .thenReturn("true");
            when(req.headers()).thenReturn(headers);
            when(req.remoteAddress()).thenReturn(remoteAddr);
            when(remoteAddr.host()).thenReturn("127.0.0.1");

            SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

            assertFalse(identity != null && !identity.isAnonymous());
        }
    }
}
