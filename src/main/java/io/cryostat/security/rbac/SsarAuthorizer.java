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
import java.util.Optional;

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributes;
import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * Builds the {@link SecurityIdentity} for a user-path request in {@link RbacMode#OPENSHIFT} mode,
 * authorizing each required permission against the Kubernetes API with a {@code
 * SelfSubjectAccessReview} performed as the caller.
 *
 * <p>Separated from {@link RbacHttpAuthenticationMechanism} because the two answer different
 * questions. The mechanism decides <em>which kind of caller</em> this is; this class decides
 * <em>what a user caller may do</em>, which is the only branch that talks to the API server and the
 * only one that needs {@link SsarClientCache}, {@link SsarDecisionCache}, and {@link
 * PermissionMapper}.
 */
@ApplicationScoped
public class SsarAuthorizer {

    static final String BEARER_PREFIX = "bearer ";
    static final String ATTR_RAW_ACCESS_TOKEN = "raw_access_token";

    @Inject Logger log;
    @Inject RbacConfig config;
    @Inject SsarClientCache ssarClientCache;
    @Inject SsarDecisionCache ssarDecisionCache;
    @Inject PermissionMapper permissionMapper;

    /**
     * Extracts the access token from the request, trying {@link ProxyHeaders#FORWARDED_TOKEN} first
     * and falling back to a {@code Bearer} token in the {@link ProxyHeaders#AUTHORIZATION} header
     * (the latter is used by oauth-proxy for programmatic API clients).
     */
    static String extractAccessToken(RoutingContext context) {
        String token = context.request().getHeader(ProxyHeaders.FORWARDED_TOKEN);
        if (StringUtils.isNotBlank(token)) {
            return token;
        }
        String authz = context.request().getHeader(ProxyHeaders.AUTHORIZATION);
        if (StringUtils.isNotBlank(authz) && authz.toLowerCase().startsWith(BEARER_PREFIX)) {
            return authz.substring(BEARER_PREFIX.length()).strip();
        }
        return null;
    }

    /**
     * Builds an identity carrying a permission checker that performs a SelfSubjectAccessReview for
     * each required permission.
     *
     * <p>Quarkus processes {@code @PermissionsAllowed(value = "resource:verb", inclusive = true)}
     * by constructing a {@link io.quarkus.security.StringPermission} with {@code name="resource"}
     * and {@code actions="verb"}. If multiple actions are present, they are comma-separated. The
     * permission name and each action are recombined here into individual {@code "resource:verb"}
     * values that {@link PermissionMapper} expects for config-key lookup.
     */
    public SecurityIdentity buildIdentity(String user, String rawToken) {
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
                            var checks =
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
                                            .toList();
                            if (checks.isEmpty()) {
                                return checkSsarPermission(rawToken, permission.getName());
                            }
                            return Uni.combine()
                                    .all()
                                    .unis(checks)
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
                .<Boolean>item(() -> performSsarCheck(rawToken, permissionName, k8s))
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .onFailure()
                .recoverWithItem(
                        e -> {
                            log.warnf(
                                    e,
                                    "OPENSHIFT mode: SSAR call failed for permission '%s'"
                                            + " (%s/%s:%s), denying",
                                    permissionName,
                                    k8s.resource(),
                                    k8s.subresource(),
                                    k8s.verb());
                            return false;
                        });
    }

    private boolean performSsarCheck(
            String rawToken, String permissionName, PermissionMapper.K8sResourceVerb k8s) {
        return ssarDecisionCache.get(
                rawToken,
                k8s.resource(),
                k8s.subresource(),
                k8s.verb(),
                key -> {
                    var result =
                            ssarClientCache.withClient(
                                    rawToken,
                                    client ->
                                            client.authorization()
                                                    .v1()
                                                    .selfSubjectAccessReview()
                                                    .create(buildSsar(k8s, config.namespace())));
                    boolean decision = Boolean.TRUE.equals(result.getStatus().getAllowed());
                    String scopeInfo =
                            config.namespace().isPresent()
                                    ? String.format("(namespace: %s)", config.namespace().get())
                                    : "(cluster-scoped)";
                    log.debugf(
                            "OPENSHIFT mode: SSAR result for permission '%s'"
                                    + " (%s/%s:%s) %s → allowed=%b",
                            permissionName,
                            k8s.resource(),
                            k8s.subresource(),
                            k8s.verb(),
                            scopeInfo,
                            decision);
                    return decision;
                });
    }

    private static SelfSubjectAccessReview buildSsar(
            PermissionMapper.K8sResourceVerb k8s, Optional<String> namespace) {
        ResourceAttributesBuilder specBuilder =
                new ResourceAttributesBuilder()
                        .withResource(k8s.resource())
                        .withSubresource(k8s.subresource())
                        .withVerb(k8s.verb());
        if (namespace.isPresent()) {
            specBuilder.withNamespace(namespace.get());
        }
        ResourceAttributes spec = specBuilder.build();
        return new SelfSubjectAccessReviewBuilder()
                .withNewSpec()
                .withResourceAttributes(spec)
                .endSpec()
                .build();
    }
}
