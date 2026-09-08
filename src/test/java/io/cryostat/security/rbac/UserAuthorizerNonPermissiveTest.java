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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.security.Permission;
import java.util.Map;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(UserAuthorizerNonPermissiveTest.NonPermissiveProfile.class)
class UserAuthorizerNonPermissiveTest {

    public static class NonPermissiveProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cryostat.security.rbac.mode", "OPENSHIFT");
        }
    }

    @Inject UserAuthorizer authorizer;

    @InjectMock SecurityIdentity securityIdentity;

    @Test
    void allowsWhenCheckPasses() {
        when(securityIdentity.checkPermission(any(Permission.class)))
                .thenReturn(Uni.createFrom().item(true));
        assertDoesNotThrow(() -> authorizer.assertAuthorized("credentials", "write"));
    }

    @Test
    void throwsForbiddenWhenCheckFails() {
        when(securityIdentity.checkPermission(any(Permission.class)))
                .thenReturn(Uni.createFrom().item(false));
        assertThrows(
                ForbiddenException.class,
                () -> authorizer.assertAuthorized("credentials", "write"));
    }
}
