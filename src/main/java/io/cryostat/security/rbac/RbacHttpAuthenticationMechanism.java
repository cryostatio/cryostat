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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Permission;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.cryostat.ConfigProperties;

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributes;
import io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 *   <li>{@code OPENSHIFT}: reads {@code X-Forwarded-User} and an access token from either {@code
 *       X-Forwarded-Access-Token} or the {@code Authorization: Bearer} header (the latter is used
 *       by oauth-proxy for programmatic API clients); attaches a per-permission SSAR checker using
 *       the token; returns {@code null} if either value is absent.
 * </ul>
 *
 * <p>Each of the two inbound paths proves itself by <em>possession</em> of a shared secret rather
 * than by the presence of a header or by its peer address, neither of which can distinguish the two
 * proxies from each other or from any other in-pod caller:
 *
 * <ul>
 *   <li>the agent gateway stamps {@code X-Cryostat-Agent-Auth} with the value of {@link
 *       ConfigProperties#AGENT_GATEWAY_SECRET}. A request presenting it is granted a restricted
 *       identity scoped to the configured {@link RbacConfig#agentPermissions() agent permission
 *       set}. When the secret is unconfigured the Agent principal is never granted.
 *   <li>the proxy on the user path stamps {@code X-Cryostat-User-Proxy-Auth} with the value of
 *       {@link ConfigProperties#USER_PROXY_SECRET}. When that secret is configured, a request
 *       bearing neither stamp is rejected rather than defaulting into the user path. When it is
 *       unconfigured, user-path provenance falls back to inference: fail open, selected by
 *       configuration rather than by anything on the request. This is the case for development
 *       environments, Docker/Podman, etc. where there is only one expected entrypoint for traffic
 *       and this entrypoint is expected to be guarded by a single auth proxy as needed.
 * </ul>
 *
 * <p>Stamps are compared in constant time. Each proxy should clear the other's stamp, so a stamp
 * presented on the wrong path is inert.
 */
@ApplicationScoped
public class RbacHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    static final String HEADER_FORWARDED_USER = "X-Forwarded-User";
    static final String HEADER_FORWARDED_TOKEN = "X-Forwarded-Access-Token";
    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String BEARER_PREFIX = "bearer ";
    public static final String HEADER_AGENT_AUTH = "X-Cryostat-Agent-Auth";
    public static final String HEADER_USER_PROXY_AUTH = "X-Cryostat-User-Proxy-Auth";
    static final String ATTR_RAW_ACCESS_TOKEN = "raw_access_token";
    static final String AGENT_PRINCIPAL = "cryostat-agent";

    @Inject Logger log;
    @Inject RbacConfig config;
    @Inject SsarClientCache ssarClientCache;
    @Inject SsarDecisionCache ssarDecisionCache;
    @Inject PermissionMapper permissionMapper;

    @ConfigProperty(name = ConfigProperties.AGENT_GATEWAY_SECRET)
    Optional<String> agentGatewaySecret;

    @ConfigProperty(name = ConfigProperties.USER_PROXY_SECRET)
    Optional<String> userProxySecret;

    private byte[] gatewaySecretBytes;
    private byte[] userProxySecretBytes;
    private Set<String> agentPermissions;

    @PostConstruct
    void validateProvenanceConfig() {
        agentPermissions =
                config.agentPermissions().orElse(List.of()).stream()
                        .map(StringUtils::strip)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toUnmodifiableSet());
        log.debugf("Agent gateway requests will be granted permissions %s", agentPermissions);

        gatewaySecretBytes = toBytesOrNull(agentGatewaySecret);
        userProxySecretBytes = toBytesOrNull(userProxySecret);
        if (gatewaySecretBytes == null) {
            log.warn("No agent gateway secret configured; Agent principal will not be granted");
        }
        if (userProxySecretBytes == null) {
            log.warn(
                    "No user proxy secret configured; user-path provenance will be inferred from"
                            + " the absence of an agent stamp rather than proven");
        }
    }

    private static byte[] toBytesOrNull(Optional<String> secret) {
        return secret.filter(StringUtils::isNotBlank)
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .orElse(null);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context, IdentityProviderManager identityProviderManager) {
        sanitizeAgentHeaders(context);

        // PERMISSIVE grants everything
        if (config.mode() == RbacMode.PERMISSIVE) {
            String user = context.request().getHeader(HEADER_FORWARDED_USER);
            return Uni.createFrom()
                    .item(buildPermissiveIdentity(StringUtils.isBlank(user) ? "" : user));
        }

        // Checked next: the stamp is unforgeable, so it is a strong
        if (isTrustedGatewayRequest(context)) {
            log.debug("Agent gateway stamp verified, granting agent identity");
            return Uni.createFrom().item(buildAgentIdentity());
        }

        // Positively identified as the user path by secret, or inferred to be the user path when no
        // secret is configured.
        // If the secret is configured and the request doesn't carry the stamp, reject it.
        if (!isTrustedUserPathRequest(context)) {
            log.debug("Request carries neither provenance stamp, returning null");
            return Uni.createFrom().nullItem();
        }

        return switch (config.mode()) {
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
                String token = extractAccessToken(context);
                if (StringUtils.isBlank(user) || StringUtils.isBlank(token)) {
                    log.debug("OPENSHIFT mode: missing user or access token, returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("OPENSHIFT mode: authenticated user %s", user);
                context.put(ATTR_RAW_ACCESS_TOKEN, token);
                yield Uni.createFrom().item(buildOpenshiftIdentity(user, token));
            }
            default -> Uni.createFrom().nullItem();
        };
    }

    /**
     * Constant-time verification that this request was forwarded by the trusted Agent gateway.
     * Possession of the shared secret is the whole check: a forged, smuggled, or hand-curled header
     * without it is inert. When no secret is configured the Agent principal is unreachable.
     */
    public boolean isTrustedGatewayRequest(RoutingContext context) {
        if (gatewaySecretBytes == null) {
            return false;
        }
        String presented = context.request().getHeader(HEADER_AGENT_AUTH);
        if (StringUtils.isBlank(presented)) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), gatewaySecretBytes);
    }

    /**
     * True when the request carries the user-path stamp, or when no user-path secret is configured
     * at all. The fallback is selected by deployment configuration, never by anything on the
     * request.
     */
    private boolean isTrustedUserPathRequest(RoutingContext context) {
        if (userProxySecretBytes == null) {
            return true;
        }
        String presented = context.request().getHeader(HEADER_USER_PROXY_AUTH);
        return StringUtils.isNotBlank(presented)
                && MessageDigest.isEqual(
                        presented.getBytes(StandardCharsets.UTF_8), userProxySecretBytes);
    }

    /**
     * Removes the agent provenance stamp from a request that also carries oauth-proxy identity
     * headers. The two are mutually exclusive by construction: the gateway clears {@code
     * X-Forwarded-*} and the auth-strip proxy clears {@code X-Cryostat-Agent-Auth}, so seeing both
     * means one of those hops was bypassed. Log it as an anomaly.
     */
    private void sanitizeAgentHeaders(RoutingContext context) {
        if (StringUtils.isNotBlank(context.request().getHeader(HEADER_FORWARDED_USER))
                && StringUtils.isNotBlank(context.request().getHeader(HEADER_AGENT_AUTH))) {
            log.warn(
                    "Request carries both oauth-proxy and agent gateway headers; stripping agent"
                            + " headers");
            context.request().headers().remove(HEADER_AGENT_AUTH);
        }
    }

    /**
     * Extracts the access token from the request, trying {@code X-Forwarded-Access-Token} first and
     * falling back to a {@code Bearer} token in the {@code Authorization} header.
     */
    static String extractAccessToken(RoutingContext context) {
        String token = context.request().getHeader(HEADER_FORWARDED_TOKEN);
        if (StringUtils.isNotBlank(token)) {
            return token;
        }
        String authz = context.request().getHeader(HEADER_AUTHORIZATION);
        if (StringUtils.isNotBlank(authz) && authz.toLowerCase().startsWith(BEARER_PREFIX)) {
            return authz.substring(BEARER_PREFIX.length()).strip();
        }
        return null;
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
                .addPermissionChecker(_ -> Uni.createFrom().item(true))
                .build();
    }

    /**
     * Builds the restricted identity granted to requests bearing a valid agent gateway stamp.
     * Unlike {@link #buildPermissiveIdentity(String)}, its permission checker only grants a request
     * when every {@code resource:verb} it requires is present in the configured {@link
     * RbacConfig#agentPermissions() agent permission set}. Blank permissions and any {@code
     * resource:verb} outside that set are denied.
     */
    private SecurityIdentity buildAgentIdentity() {
        Set<String> allowed = agentPermissions;
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(AGENT_PRINCIPAL))
                .setAnonymous(false)
                .addPermissionChecker(
                        (Permission permission) -> {
                            String resource = permission.getName();
                            String actions = permission.getActions();
                            if (StringUtils.isBlank(resource) || StringUtils.isBlank(actions)) {
                                return Uni.createFrom().item(false);
                            }
                            List<String> required =
                                    Arrays.stream(actions.split(","))
                                            .map(StringUtils::strip)
                                            .filter(StringUtils::isNotBlank)
                                            .map(action -> resource + ":" + action)
                                            .toList();
                            boolean granted = !required.isEmpty() && allowed.containsAll(required);
                            return Uni.createFrom().item(granted);
                        })
                .build();
    }

    /**
     * Builds an identity carrying a permission checker that performs a SelfSubjectAccessReview for
     * each required permission. Used in OPENSHIFT mode.
     *
     * <p>Quarkus processes {@code @PermissionsAllowed(value = "resource:verb", inclusive = true)}
     * by constructing a {@link io.quarkus.security.StringPermission} with {@code name="resource"}
     * and {@code actions="verb"}. If multiple actions are present, they are comma-separated. The
     * permission name and each action are recombined here into individual {@code "resource:verb"}
     * values that {@link PermissionMapper} expects for config-key lookup.
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
                _ -> {
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
