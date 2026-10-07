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

import java.util.Optional;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
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
    @Inject PermissionMapper permissionMapper;
    @Inject SsarDecisionCache ssarDecisionCache;
    @Inject SsarClientCache ssarClientCache;

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

    /**
     * Checks whether the caller identified by {@code rawToken} holds the named permission in the
     * specified {@code namespace}, returning a boolean rather than throwing.
     *
     * <p>In {@link RbacMode#PERMISSIVE} and {@link RbacMode#BASIC} modes this always returns {@code
     * true}. In {@link RbacMode#OPENSHIFT} mode a namespace-scoped SelfSubjectAccessReview is
     * performed (or a cache hit is returned).
     *
     * @param resource the resource part of the permission, e.g. {@code archivedrecordings}
     * @param action the action part of the permission, e.g. {@code read}
     * @param namespace the Kubernetes namespace in which to scope the SSAR; empty for
     *     cluster-scoped
     * @param rawToken the caller's raw bearer token
     * @return {@code true} if the permission is granted (or mode is not OPENSHIFT)
     */
    public boolean isAuthorized(String resource, String action, String namespace, String rawToken) {
        if (rbacConfig.mode() != RbacMode.OPENSHIFT) {
            return true;
        }
        var mapping = permissionMapper.resolve(resource + ":" + action);
        if (mapping.isEmpty()) {
            return false;
        }
        var k8s = mapping.get();
        return ssarDecisionCache.get(
                rawToken,
                k8s.resource(),
                k8s.subresource(),
                k8s.verb(),
                namespace,
                key -> {
                    SelfSubjectAccessReview ssar =
                            SsarAuthorizer.buildSsar(k8s, Optional.of(namespace));
                    var result =
                            ssarClientCache.withClient(
                                    rawToken,
                                    client ->
                                            client.authorization()
                                                    .v1()
                                                    .selfSubjectAccessReview()
                                                    .create(ssar));
                    return Boolean.TRUE.equals(result.getStatus().getAllowed());
                });
    }
}
