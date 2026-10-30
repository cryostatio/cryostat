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

import java.time.Duration;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.TimeoutException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.jboss.logging.Logger;

/**
 * Centralises the RBAC mode-guard + permission check used throughout Cryostat request handlers.
 *
 * <p>In {@link RbacMode#PERMISSIVE} mode every call is a no-op. In all other modes a {@link
 * io.quarkus.security.identity.SecurityIdentity#checkPermission} call is made and a {@link
 * ForbiddenException} is thrown when the check fails. The check waits at most {@link
 * #RBAC_CHECK_TIMEOUT}; a check that does not complete in that time is treated as a denial.
 */
@ApplicationScoped
public class UserAuthorizer {

    public static final Duration RBAC_CHECK_TIMEOUT = Duration.ofSeconds(5);

    @Inject RbacConfig rbacConfig;
    @Inject SecurityIdentity securityIdentity;
    @Inject Logger logger;

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
        boolean allowed;
        try {
            allowed =
                    securityIdentity
                            .checkPermission(PermissionMapper.toPermission(resource, action))
                            .await()
                            .atMost(RBAC_CHECK_TIMEOUT);
        } catch (TimeoutException e) {
            logger.warnf(
                    "Permission check timed out after %s: %s:%s, denying",
                    RBAC_CHECK_TIMEOUT, resource, action);
            allowed = false;
        }
        if (!allowed) {
            throw new ForbiddenException(resource + ":" + action);
        }
    }
}
