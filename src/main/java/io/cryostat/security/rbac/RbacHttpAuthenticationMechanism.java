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

import java.security.Permission;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * Custom {@link HttpAuthenticationMechanism} that establishes the {@link SecurityIdentity} from
 * reverse-proxy forwarded headers according to the configured {@link RbacMode}.
 *
 * <p>Identities are built directly (not via {@code IdentityProviderManager}) so all permission
 * granting must be done here rather than in a {@link
 * io.quarkus.security.identity.SecurityIdentityAugmentor}, which is only invoked for identities
 * produced through the provider manager pipeline.
 *
 * <ul>
 *   <li>{@code PERMISSIVE}: returns an authenticated identity with all permissions pre-granted for
 *       every request, using the forwarded user header as the principal if present.
 *   <li>{@code BASIC}: reads {@code X-Forwarded-User}; returns an authenticated identity if
 *       present, or {@code null} (unauthenticated) otherwise — Quarkus then challenges with 401.
 *   <li>{@code OPENSHIFT}: reads both {@code X-Forwarded-User} and {@code
 *       X-Forwarded-Access-Token}; attaches a per-permission SSAR checker using the token; returns
 *       {@code null} if either header is absent.
 * </ul>
 */
@ApplicationScoped
public class RbacHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    static final String HEADER_FORWARDED_USER = "X-Forwarded-User";
    static final String HEADER_FORWARDED_TOKEN = "X-Forwarded-Access-Token";
    static final String ATTR_RAW_ACCESS_TOKEN = "raw_access_token";

    @Inject Logger log;
    @Inject RbacConfig config;
    @Inject SsarClientCache ssarClientCache;
    @Inject PermissionMapper permissionMapper;

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context, IdentityProviderManager identityProviderManager) {
        return switch (config.mode()) {
            case PERMISSIVE -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                yield Uni.createFrom()
                        .item(buildPermissiveIdentity(StringUtils.isBlank(user) ? "" : user));
            }
            case BASIC -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                if (StringUtils.isBlank(user)) {
                    log.debug("BASIC mode: no X-Forwarded-User header, returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("BASIC mode: authenticated user %s", user);
                yield Uni.createFrom().item(buildPermissiveIdentity(user));
            }
            case OPENSHIFT -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                String token = context.request().getHeader(HEADER_FORWARDED_TOKEN);
                if (StringUtils.isBlank(user) || StringUtils.isBlank(token)) {
                    log.debug(
                            "OPENSHIFT mode: missing X-Forwarded-User or X-Forwarded-Access-Token,"
                                    + " returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("OPENSHIFT mode: authenticated user %s", user);
                yield Uni.createFrom().item(buildOpenshiftIdentity(user, token));
            }
        };
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return 2000;
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Collections.emptySet();
    }

    /**
     * Builds an identity with a permission checker that grants every request unconditionally. Used
     * in PERMISSIVE and BASIC modes where no per-permission authorization check is desired: if the
     * auth proxy has passed the request to Cryostat, then the request has already been
     * authenticated and authorized.
     */
    private static SecurityIdentity buildPermissiveIdentity(String user) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user))
                .setAnonymous(false)
                .addPermissionChecker(permission -> Uni.createFrom().item(true))
                .build();
    }

    /**
     * Builds an identity carrying a permission checker that performs a SelfSubjectAccessReview for
     * each required permission. Used in OPENSHIFT mode.
     *
     * <p>Quarkus processes {@code @PermissionsAllowed("resource:verb")} by constructing a {@link
     * io.quarkus.security.StringPermission} with {@code name="resource"} and {@code
     * actions="verb"}. If multiple actions are present, they are comma-separated. The permission
     * name and each action are recombined here into individual {@code "resource:verb"} values that
     * {@link PermissionMapper} expects for config-key lookup.
     */
    private SecurityIdentity buildOpenshiftIdentity(String user, String rawToken) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user))
                .setAnonymous(false)
                .addAttribute(ATTR_RAW_ACCESS_TOKEN, rawToken)
                .addPermissionChecker(
                        (Permission permission) -> {
                            String actions = permission.getActions();
                            if (StringUtils.isBlank(actions)) {
                                return checkSsarPermission(rawToken, permission.getName());
                            }
                            return Uni.combine()
                                    .all()
                                    .unis(
                                            Arrays.stream(actions.split(","))
                                                    .map(StringUtils::strip)
                                                    .filter(StringUtils::isNotBlank)
                                                    .map(
                                                            action ->
                                                                    checkSsarPermission(
                                                                            rawToken,
                                                                            permission.getName()
                                                                                    + ":"
                                                                                    + action))
                                                    .toList())
                                    .with(
                                            results ->
                                                    results.stream()
                                                            .allMatch(Boolean.TRUE::equals));
                        })
                .build();
    }

    /**
     * Performs a {@code SelfSubjectAccessReview} for {@code permissionName} using the caller's
     * bearer token. The Fabric8 HTTP call is dispatched to a worker thread so that it never blocks
     * the Vert.x event-loop thread.
     *
     * <p>Returns {@code false} (deny) when:
     *
     * <ul>
     *   <li>no mapping is configured for {@code permissionName},
     *   <li>the SSAR response returns {@code allowed=false}, or
     *   <li>the SSAR call throws (network error, TLS failure, API server unavailable, etc.).
     * </ul>
     */
    private Uni<Boolean> checkSsarPermission(String rawToken, String permissionName) {
        var mapping = permissionMapper.resolve(permissionName);
        if (mapping.isEmpty()) {
            log.debugf(
                    "OPENSHIFT mode: no mapping and no default-permission configured for '%s',"
                            + " denying",
                    permissionName);
            return Uni.createFrom().item(false);
        }
        var k8s = mapping.get();
        log.debugf(
                "OPENSHIFT mode: checking SSAR for permission '%s' → %s/%s:%s",
                permissionName, k8s.resource(), k8s.subresource(), k8s.verb());
        return Uni.createFrom()
                .<Boolean>emitter(
                        em -> {
                            try {
                                var client = ssarClientCache.getOrCreate(rawToken);
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
                                boolean allowed =
                                        Boolean.TRUE.equals(result.getStatus().getAllowed());
                                log.debugf(
                                        "OPENSHIFT mode: SSAR result for permission '%s'"
                                                + " (%s/%s:%s) → allowed=%b",
                                        permissionName,
                                        k8s.resource(),
                                        k8s.subresource(),
                                        k8s.verb(),
                                        allowed);
                                em.complete(allowed);
                            } catch (Exception e) {
                                log.warnf(
                                        e,
                                        "OPENSHIFT mode: SSAR call failed for permission '%s'"
                                                + " (%s/%s:%s), denying",
                                        permissionName,
                                        k8s.resource(),
                                        k8s.subresource(),
                                        k8s.verb());
                                em.complete(false);
                            }
                        })
                .runSubscriptionOn(Infrastructure.getDefaultExecutor());
    }
}
