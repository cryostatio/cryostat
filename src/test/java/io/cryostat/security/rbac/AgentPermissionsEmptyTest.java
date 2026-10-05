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
 * An administrator may set the agent permission set to an explicitly empty value, which must yield
 * an empty permitted set rather than falling back to the built-in default: the Agent still
 * authenticates, and every authorized endpoint denies it.
 */
@QuarkusTest
@TestProfile(AgentPermissionsEmptyTest.EmptyAgentPermissionsProfile.class)
class AgentPermissionsEmptyTest {

    static final String GATEWAY_SECRET = "gateway-secret-value";

    public static class EmptyAgentPermissionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "OPENSHIFT",
                    "cryostat.security.rbac.default-permission", "pods/exec:create",
                    "cryostat.security.rbac.agent-permissions", "",
                    "cryostat.security.agent-gateway.secret", GATEWAY_SECRET);
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testAgentIdentityGrantedButDeniedEveryPermission() {
        var ctx = MockRequests.context(ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET);

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertTrue(identity.getPrincipal().getName().equals("cryostat-agent"));
        assertFalse(
                identity.checkPermission(new StringPermission("discoverynodes", "write"))
                        .await()
                        .indefinitely());
        assertFalse(
                identity.checkPermission(new StringPermission("targets", "read"))
                        .await()
                        .indefinitely());
    }
}
