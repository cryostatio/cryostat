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
 * Neither provenance secret is configured: the Helm posture. The Agent principal must be
 * unreachable whatever the request carries, and unstamped user-path requests must still be
 * accepted.
 */
@QuarkusTest
@TestProfile(AgentGatewaySecretUnconfiguredTest.NoSecretsProfile.class)
class AgentGatewaySecretUnconfiguredTest {

    public static class NoSecretsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "OPENSHIFT",
                    "cryostat.security.rbac.default-permission", "pods/exec:create");
        }
    }

    @Inject RbacHttpAuthenticationMechanism mechanism;

    @Test
    void testAgentStampIgnoredWhenNoGatewaySecretConfigured() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, "any-value-at-all");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testBlankAgentStampIgnoredWhenNoGatewaySecretConfigured() {
        var ctx = MockRequests.context(RbacHttpAuthenticationMechanism.HEADER_AGENT_AUTH, "");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertFalse(identity != null && !identity.isAnonymous());
    }

    @Test
    void testUnstampedUserRequestStillAccepted() {
        var ctx =
                MockRequests.context(
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_USER, "admin",
                        RbacHttpAuthenticationMechanism.HEADER_FORWARDED_TOKEN, "my-token");

        SecurityIdentity identity = mechanism.authenticate(ctx, null).await().indefinitely();

        assertNotNull(identity);
        assertFalse(identity.isAnonymous());
        assertTrue(identity.getPrincipal().getName().equals("admin"));
    }
}
