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

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

/**
 * Centralises the RBAC mode-guard + permission check used throughout Cryostat request handlers.
 *
 * <p>In {@link RbacMode#PERMISSIVE} mode every call is a no-op. In all other modes a {@link
 * io.quarkus.security.identity.SecurityIdentity#checkPermission} call is made and a {@link
 * ForbiddenException} is thrown when the check fails.
 */
@ApplicationScoped
public class UserAuthorizer {

    @Inject RbacConfig rbacConfig;
    @Inject SecurityIdentity securityIdentity;

    /**
     * Asserts that the current security identity holds the named permission.
     *
     * <p>When the RBAC mode is {@link RbacMode#PERMISSIVE} this method returns immediately without
     * any check. Otherwise, a {@link ForbiddenException} is thrown if the check fails.
     *
     * @param resource the resource part of the permission, e.g. {@code credentials}
     * @param action the action part of the permission, e.g. {@code write}
     * @throws ForbiddenException if the mode is not PERMISSIVE and the identity does not hold the
     *     permission
     */
    public void assertAuthorized(String resource, String action) {
        if (rbacConfig.mode() == RbacMode.PERMISSIVE) {
            return;
        }
        boolean allowed =
                securityIdentity
                        .checkPermission(PermissionMapper.toPermission(resource, action))
                        .await()
                        .indefinitely();
        if (!allowed) {
            throw new ForbiddenException(resource + ":" + action);
        }
    }
}
