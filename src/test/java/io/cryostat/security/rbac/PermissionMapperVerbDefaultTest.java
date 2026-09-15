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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PermissionMapperVerbDefaultTest.Profile.class)
class PermissionMapperVerbDefaultTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "PERMISSIVE",
                    // verb-level defaults
                    "cryostat.security.rbac.default-read-permission", "pods:get",
                    "cryostat.security.rbac.default-write-permission", "pods/exec:create",
                    "cryostat.security.rbac.default-delete-permission", "pods:delete",
                    // global fallback
                    "cryostat.security.rbac.default-permission", "services:get",
                    // one explicit per-permission entry to test priority
                    "cryostat.security.rbac.permissions.\"targets.read\"", "nodes:get");
        }
    }

    @Inject PermissionMapper mapper;

    @Test
    void testReadFallsBackToReadDefault() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("activerecordings:read");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("get", result.get().verb());
    }

    @Test
    void testWriteFallsBackToWriteDefault() {
        Optional<PermissionMapper.K8sResourceVerb> result =
                mapper.resolve("activerecordings:write");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("exec", result.get().subresource());
        assertEquals("create", result.get().verb());
    }

    @Test
    void testDeleteFallsBackToDeleteDefault() {
        Optional<PermissionMapper.K8sResourceVerb> result =
                mapper.resolve("activerecordings:delete");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("delete", result.get().verb());
    }

    @Test
    void testExplicitMappingTakesPriorityOverVerbDefault() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("targets:read");
        assertTrue(result.isPresent());
        assertEquals("nodes", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("get", result.get().verb());
    }

    @Test
    void testUnrecognisedVerbFallsThroughToGlobalDefault() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("targets:list");
        assertTrue(result.isPresent());
        assertEquals("services", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("get", result.get().verb());
    }
}
