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

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * <p>In {@code BASIC} and {@code OPENSHIFT} modes, requests arriving through the mTLS agent proxy
 * (identified by the {@code X-Cryostat-Agent-Proxy} header) are granted a restricted identity
 * scoped to the configured {@link RbacConfig#agentPermissions() agent permission set} when the
 * normal forwarded-user headers are absent. This pathway is gated on the {@code
 * cryostat.http.proxy.mtls.trusted-hosts} config property: the header is only accepted when the
 * property lists one or more hostnames, every listed hostname also appears in {@code
 * quarkus.http.proxy.trusted-proxies} (i.e. it acts only as a selector from the already-trusted
 * proxy list, it cannot grant trust to new proxies on its own), and the request originates from one
 * of those hosts. Multiple hostnames may be listed for the same physical host (e.g. {@code
 * localhost,127.0.0.1}). When the property is absent or blank, the header is ignored entirely. To
 * prevent header injection through the oauth-proxy path, {@code X-Cryostat-Agent-Proxy} is stripped
 * from any request that also carries {@code X-Forwarded-User}.
 */
@ApplicationScoped
public class RbacHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    static final String HEADER_FORWARDED_USER = "X-Forwarded-User";
    static final String HEADER_FORWARDED_TOKEN = "X-Forwarded-Access-Token";
    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String BEARER_PREFIX = "bearer ";
    public static final String HEADER_AGENT_PROXY = "X-Cryostat-Agent-Proxy";
    static final String ATTR_RAW_ACCESS_TOKEN = "raw_access_token";
    static final String AGENT_PRINCIPAL = "cryostat-agent";

    @Inject Logger log;
    @Inject RbacConfig config;
    @Inject SsarClientCache ssarClientCache;
    @Inject SsarDecisionCache ssarDecisionCache;
    @Inject PermissionMapper permissionMapper;

    @ConfigProperty(name = ConfigProperties.AGENT_PROXY_MTLS_TRUSTED_HOSTS)
    Optional<List<String>> trustedAgentProxyHosts;

    @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies")
    Optional<List<String>> quarkusTrustedProxies;

    private boolean agentProxyConfigValid;
    private Set<String> agentPermissions;

    @PostConstruct
    void validateAgentProxyConfig() {
        agentPermissions =
                config.agentPermissions().stream()
                        .map(StringUtils::strip)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toUnmodifiableSet());
        log.debugf("Agent proxy requests will be granted permissions %s", agentPermissions);

        List<String> hosts =
                trustedAgentProxyHosts.orElse(List.of()).stream()
                        .filter(StringUtils::isNotBlank)
                        .toList();
        if (hosts.isEmpty()) {
            agentProxyConfigValid = false;
            return;
        }
        List<String> proxies = quarkusTrustedProxies.orElse(List.of());
        List<String> untrusted = hosts.stream().filter(h -> !proxies.contains(h)).toList();
        if (!untrusted.isEmpty()) {
            log.warnf(
                    "cryostat.http.proxy.mtls.trusted-hosts %s not listed in"
                            + " quarkus.http.proxy.trusted-proxies; agent proxy header will be"
                            + " ignored",
                    untrusted);
            agentProxyConfigValid = false;
            return;
        }
        agentProxyConfigValid = true;
        log.debugf("Agent proxy header will be accepted from trusted hosts %s", hosts);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context, IdentityProviderManager identityProviderManager) {
        sanitizeAgentProxyHeader(context);
        return switch (config.mode()) {
            case PERMISSIVE -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                yield Uni.createFrom()
                        .item(buildPermissiveIdentity(StringUtils.isBlank(user) ? "" : user));
            }
            case BASIC -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                if (StringUtils.isBlank(user)) {
                    if (isAgentProxyRequest(context)) {
                        log.debug("BASIC mode: agent proxy request, granting agent identity");
                        yield Uni.createFrom().item(buildAgentIdentity());
                    }
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
                    if (isAgentProxyRequest(context)) {
                        log.debug("OPENSHIFT mode: agent proxy request, granting agent identity");
                        yield Uni.createFrom().item(buildAgentIdentity());
                    }
                    log.debug("OPENSHIFT mode: missing user or access token, returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("OPENSHIFT mode: authenticated user %s", user);
                context.put(ATTR_RAW_ACCESS_TOKEN, token);
                yield Uni.createFrom().item(buildOpenshiftIdentity(user, token));
            }
        };
    }

    /**
     * Strips {@code X-Cryostat-Agent-Proxy} from the request whenever {@code X-Forwarded-User} is
     * present. This prevents a malicious client from injecting the agent proxy header through the
     * oauth-proxy path (which cannot be configured to strip arbitrary headers) to bypass SSAR
     * authorization checks.
     */
    private void sanitizeAgentProxyHeader(RoutingContext context) {
        if (StringUtils.isNotBlank(context.request().getHeader(HEADER_FORWARDED_USER))
                && StringUtils.isNotBlank(context.request().getHeader(HEADER_AGENT_PROXY))) {
            log.debug("Stripping X-Cryostat-Agent-Proxy header: X-Forwarded-User is present");
            context.request().headers().remove(HEADER_AGENT_PROXY);
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

    public boolean isAgentProxyRequest(RoutingContext context) {
        if (StringUtils.isBlank(context.request().getHeader(HEADER_AGENT_PROXY))) {
            return false;
        }
        if (!agentProxyConfigValid) {
            log.debug(
                    "X-Cryostat-Agent-Proxy header present but agent proxy is not configured,"
                            + " ignoring");
            return false;
        }
        String remoteHost =
                context.request().remoteAddress() != null
                        ? context.request().remoteAddress().host()
                        : null;
        if (StringUtils.isBlank(remoteHost)) {
            log.debug(
                    "X-Cryostat-Agent-Proxy header present but remote address unavailable,"
                            + " ignoring");
            return false;
        }
        try {
            InetAddress remoteAddr = InetAddress.getByName(remoteHost);
            for (String host : trustedAgentProxyHosts.orElse(List.of())) {
                for (InetAddress trustedAddr : InetAddress.getAllByName(host)) {
                    if (remoteAddr.equals(trustedAddr)) {
                        return true;
                    }
                }
            }
        } catch (UnknownHostException e) {
            log.warnf(e, "Failed to resolve trusted agent proxy host");
        }
        log.debugf(
                "X-Cryostat-Agent-Proxy header present but remote address '%s' does not match"
                        + " any trusted host in %s",
                remoteHost, trustedAgentProxyHosts.orElse(List.of()));
        return false;
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
     * Builds the restricted identity granted to authenticated agent-proxy requests. Unlike {@link
     * #buildPermissiveIdentity(String)}, its permission checker only grants a request when every
     * {@code resource:verb} it requires is present in the configured {@link
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
                key -> {
                    var client = ssarClientCache.getOrCreate(rawToken);
                    var result =
                            client.authorization()
                                    .v1()
                                    .selfSubjectAccessReview()
                                    .create(buildSsar(k8s, config.namespace()));
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
