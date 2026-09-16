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

import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code PERMISSIVE} short-circuits ahead of both stamps: the mode grants everything by definition,
 * so provenance could only narrow an identity already declared unrestricted. Note the consequence —
 * the agent permission set is inoperative in this mode.
 */
@QuarkusTest
@TestProfile(UserProxyStampPermissiveTest.PermissiveProfile.class)
class UserProxyStampPermissiveTest {

    static final String GATEWAY_SECRET = "gateway-secret-value";
    static final String USER_PROXY_SECRET = "user-proxy-secret-value";

    public static class PermissiveProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "PERMISSIVE",
                    "cryostat.security.agent-gateway.secret", GATEWAY_SECRET,
                    "cryostat.security.user-proxy.secret", USER_PROXY_SECRET);
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testUnstampedRequestAccepted() {
        var ctx = MockRequests.context();

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely());
    }

    @Test
    void testAgentStampedRequestReceivesUnrestrictedIdentity() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, GATEWAY_SECRET);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        // not the agent principal, and not limited to the agent permission set
        assertFalse(identity.getPrincipal().getName().equals("cryostat-agent"));
        assertTrue(
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely());
    }
}
