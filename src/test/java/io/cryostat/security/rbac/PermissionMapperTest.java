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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PermissionMapperTest.Profile.class)
class PermissionMapperTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "PERMISSIVE",
                    "cryostat.security.rbac.permissions.\"activerecordings.read\"",
                            "pods/exec:create",
                    "cryostat.security.rbac.permissions.\"targets.read\"", "pods:get",
                    "cryostat.security.rbac.permissions.\"credentials.delete\"", "pods:delete");
        }
    }

    @Inject PermissionMapper mapper;

    @Test
    void testResolvesSimpleResourceVerb() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("targets:read");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("get", result.get().verb());
    }

    @Test
    void testResolvesResourceWithSubresource() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("activerecordings:read");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("exec", result.get().subresource());
        assertEquals("create", result.get().verb());
    }

    @Test
    void testResolvesDeleteVerb() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("credentials:delete");
        assertTrue(result.isPresent());
        assertEquals("pods", result.get().resource());
        assertEquals("", result.get().subresource());
        assertEquals("delete", result.get().verb());
    }

    @Test
    void testReturnsEmptyForUnknownPermission() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("unknown:action");
        assertFalse(result.isPresent());
    }

    @Test
    void testReturnsEmptyForBlankPermission() {
        assertFalse(mapper.resolve("").isPresent());
        assertFalse(mapper.resolve(null).isPresent());
        assertFalse(mapper.resolve("   ").isPresent());
    }

    @Test
    void testColonToDotsConversion() {
        Optional<PermissionMapper.K8sResourceVerb> result = mapper.resolve("targets:read");
        assertTrue(result.isPresent());
    }
}
