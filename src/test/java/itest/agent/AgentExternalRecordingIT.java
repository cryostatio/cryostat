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
package itest.agent;

import java.util.Map;

import io.cryostat.resources.AgentExternalRecordingApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration test for external recording detection using the Cryostat agent. Tests that recordings
 * started externally (not via Cryostat API) are properly detected, labeled, and managed according
 * to external recording configuration.
 */
@QuarkusIntegrationTest
@TestProfile(AgentExternalRecordingIT.class)
@QuarkusTestResource(
        value = AgentExternalRecordingApplicationResource.class,
        restrictToAnnotatedClass = true)
@EnabledIfEnvironmentVariable(
        named = "PR_CI",
        matches = "true",
        disabledReason =
                "Runs well in PR CI under Docker, but not on main CI or locally under Podman")
public class AgentExternalRecordingIT extends AgentTestBase implements QuarkusTestProfile {

    /** Configure external recording detection settings for this test. */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cryostat.external-recordings.delay",
                "2s",
                "cryostat.external-recordings.archive",
                "true",
                "cryostat.external-recordings.autoanalyze",
                "true");
    }

    @Test
    void testExternalRecordingDetectionAndLifecycle() throws Exception {
        // Test implementation will be added in phases
        System.err.println("Testing external recording on agent target: " + target.alias());
        System.err.println("Target ID: " + target.id());
    }
}
