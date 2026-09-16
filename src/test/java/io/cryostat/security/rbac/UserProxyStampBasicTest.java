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
 * Both provenance secrets configured, {@code BASIC} mode. A forged {@code X-Forwarded-User} from an
 * in-pod caller no longer yields a permissive identity, because the request never reaches the mode
 * switch without the user-path stamp.
 */
@QuarkusTest
@TestProfile(UserProxyStampBasicTest.BasicProfile.class)
class UserProxyStampBasicTest {

    static final String GATEWAY_SECRET = "gateway-secret-value";
    static final String USER_PROXY_SECRET = "user-proxy-secret-value";

    public static class BasicProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "BASIC",
                    "cryostat.security.agent-gateway.secret", GATEWAY_SECRET,
                    "cryostat.security.user-proxy.secret", USER_PROXY_SECRET);
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testStampedUserRequestAccepted() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_USER_PROXY_AUTH,
                        USER_PROXY_SECRET,
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER,
                        "admin");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }

    @Test
    void testUnstampedForgedUserRequestRejected() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testAgentStampGrantsAgentIdentity() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, GATEWAY_SECRET);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertTrue(identity.getPrincipal().getName().equals("cryostat-agent"));
        assertTrue(
                identity.checkPermission(new StringPermission("discoverynodes", "write"))
                        .await()
                        .indefinitely());
        assertFalse(
                identity.checkPermission(new StringPermission("credentials", "read"))
                        .await()
                        .indefinitely());
    }
}
