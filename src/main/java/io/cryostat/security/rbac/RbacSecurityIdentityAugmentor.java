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

import java.util.Map;

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

/**
 * Augments the {@link SecurityIdentity} with a dynamic permission checker for identities that
 * arrive through the {@code IdentityProviderManager} pipeline (i.e. BASIC and OPENSHIFT modes).
 *
 * <p>Note: identities produced directly by {@link RbacHttpAuthenticationMechanism} do NOT pass
 * through augmentors. PERMISSIVE-mode permission granting is therefore handled in the mechanism
 * itself via {@link RbacHttpAuthenticationMechanism#ALL_PERMISSIONS}.
 *
 * <ul>
 *   <li>{@code PERMISSIVE}: no-op; permissions already granted by the mechanism.
 *   <li>{@code BASIC}: authenticated users receive all permissions; anonymous users receive none.
 *   <li>{@code OPENSHIFT}: per-permission SelfSubjectAccessReview checks are performed against the
 *       OpenShift API server using the caller's bearer token.
 * </ul>
 */
@ApplicationScoped
public class RbacSecurityIdentityAugmentor implements SecurityIdentityAugmentor {

    static final String RAW_ACCESS_TOKEN_ATTRIBUTE = "raw_access_token";

    @Inject RbacConfig config;
    @Inject SsarClientCache clientCache;
    @Inject PermissionMapper permissionMapper;

    @Override
    public Uni<SecurityIdentity> augment(
            SecurityIdentity identity, AuthenticationRequestContext context) {
        if (config.mode() == RbacMode.PERMISSIVE) {
            return Uni.createFrom().item(grantAll(identity));
        }

        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        if (config.mode() == RbacMode.BASIC) {
            return Uni.createFrom().item(grantAll(identity));
        }

        if (config.mode() != RbacMode.OPENSHIFT) {
            throw new IllegalStateException();
        }

        String rawToken = identity.getAttribute(RAW_ACCESS_TOKEN_ATTRIBUTE);
        if (StringUtils.isBlank(rawToken)) {
            return Uni.createFrom().item(identity);
        }

        return Uni.createFrom()
                .item(
                        QuarkusSecurityIdentity.builder(identity)
                                .addPermissionChecker(
                                        permission ->
                                                checkSsarPermission(rawToken, permission.getName()))
                                .build());
    }

    @Override
    public Uni<SecurityIdentity> augment(
            SecurityIdentity identity,
            AuthenticationRequestContext context,
            Map<String, Object> attributes) {
        return augment(identity, context);
    }

    private SecurityIdentity grantAll(SecurityIdentity identity) {
        return QuarkusSecurityIdentity.builder(identity)
                .addPermissionChecker(permission -> Uni.createFrom().item(true))
                .build();
    }

    private Uni<Boolean> checkSsarPermission(String rawToken, String permissionName) {
        return permissionMapper
                .resolve(permissionName)
                .map(
                        k8s -> {
                            try {
                                var client = clientCache.getOrCreate(rawToken);
                                var spec =
                                        new ResourceAttributesBuilder()
                                                .withResource(k8s.resource())
                                                .withSubresource(k8s.subresource())
                                                .withVerb(k8s.verb())
                                                .build();
                                var review =
                                        new SelfSubjectAccessReviewBuilder()
                                                .withNewSpec()
                                                .withResourceAttributes(spec)
                                                .endSpec()
                                                .build();
                                var result =
                                        client.authorization()
                                                .v1()
                                                .selfSubjectAccessReview()
                                                .create(review);
                                boolean allowed = result.getStatus().getAllowed();
                                return Uni.createFrom().item(allowed);
                            } catch (Exception e) {
                                return Uni.createFrom().<Boolean>item(false);
                            }
                        })
                .orElseGet(() -> Uni.createFrom().item(config.mode() == RbacMode.PERMISSIVE));
    }
}
