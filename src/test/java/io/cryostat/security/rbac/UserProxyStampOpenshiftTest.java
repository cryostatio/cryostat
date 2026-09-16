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

import java.util.Map;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Both provenance secrets configured, {@code OPENSHIFT} mode: the Operator posture. A request
 * bearing neither stamp is rejected rather than inheriting the user path, and neither stamp is
 * accepted on the other path's header.
 */
@QuarkusTest
@TestProfile(UserProxyStampOpenshiftTest.BothSecretsProfile.class)
class UserProxyStampOpenshiftTest {

    static final String GATEWAY_SECRET = "gateway-secret-value";
    static final String USER_PROXY_SECRET = "user-proxy-secret-value";

    public static class BothSecretsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode",
                    "OPENSHIFT",
                    "cryostat.security.rbac.default-permission",
                    "pods/exec:create",
                    "cryostat.security.agent-gateway.secret",
                    GATEWAY_SECRET,
                    "cryostat.security.user-proxy.secret",
                    USER_PROXY_SECRET);
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testStampedUserRequestAccepted() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_USER_PROXY_AUTH, USER_PROXY_SECRET,
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }

    @Test
    void testUnstampedUserRequestRejected() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testWrongUserStampRejected() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_USER_PROXY_AUTH, "not-the-secret",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testAgentStampAcceptedWithoutUserStamp() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, GATEWAY_SECRET);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("cryostat-agent"));
    }

    @Test
    void testAgentSecretPresentedOnUserPathGrantsNothing() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_USER_PROXY_AUTH, GATEWAY_SECRET,
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testUserProxySecretPresentedOnAgentPathGrantsNothing() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, USER_PROXY_SECRET);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }
}
