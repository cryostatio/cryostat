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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * Custom {@link HttpAuthenticationMechanism} that establishes the {@link SecurityIdentity} from
 * reverse-proxy forwarded headers, according to the request's {@link ProvenancePath provenance} and
 * the configured {@link RbacMode}.
 *
 * <p>Identities are built directly (not via {@code IdentityProviderManager}) so all permission
 * granting must be done here rather than in a {@link
 * io.quarkus.security.identity.SecurityIdentityAugmentor}, which is only invoked for identities
 * produced through the provider manager pipeline.
 *
 * <p>Dispatch proceeds in two steps. {@link RequestProvenance} first establishes which proxy
 * forwarded the request; only then is the mode consulted to decide what that caller may do:
 *
 * <ul>
 *   <li>{@link ProvenancePath#AGENT}: the restricted Agent identity, scoped to the configured
 *       {@link RbacConfig#agentPermissions() agent permission set}.
 *   <li>{@link ProvenancePath#UNTRUSTED}: {@code null} (unauthenticated) - a request that crossed
 *       neither proxy is rejected rather than defaulting into the user path.
 *   <li>{@link ProvenancePath#USER} in {@code BASIC}: reads {@link ProxyHeaders#FORWARDED_USER};
 *       returns an authenticated identity if present, or {@code null} otherwise - Quarkus then
 *       challenges with 401.
 *   <li>{@link ProvenancePath#USER} in {@code OPENSHIFT}: reads {@link ProxyHeaders#FORWARDED_USER}
 *       and an access token, then delegates to {@link SsarAuthorizer} for a per-permission
 *       SelfSubjectAccessReview; returns {@code null} if either value is absent.
 * </ul>
 *
 * <p>{@code PERMISSIVE} is decided ahead of provenance: the mode grants everything by definition,
 * so a provenance check could only narrow an identity that the mode has already declared
 * unrestricted. Provenance is still <em>resolved</em> first, both so that the sanitization in
 * {@link RequestProvenance#resolve} is never skipped and so that a gateway request is still named
 * {@link #AGENT_PRINCIPAL} rather than going unattributed. It is not used to restrict permissions,
 * which is why the agent permission set is inoperative in this mode.
 */
@ApplicationScoped
public class RbacHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    public static final String AGENT_PRINCIPAL = "cryostat-agent";

    /**
     * {@link SecurityIdentity#getAttribute(String) Identity attribute} set to {@link Boolean#TRUE}
     * only on identities established from {@link ProvenancePath#AGENT}. Callers that need to know a
     * request came through the agent gateway must test this rather than compare the principal name
     * to {@link #AGENT_PRINCIPAL}: in {@code PERMISSIVE} and {@code BASIC} modes the principal name
     * is taken from {@link ProxyHeaders#FORWARDED_USER}, so a user of that name would otherwise be
     * indistinguishable from the gateway.
     */
    public static final String AGENT_IDENTITY_ATTRIBUTE = "cryostat.agent-gateway";

    @Inject Logger log;
    @Inject RbacConfig config;
    @Inject RequestProvenance provenance;
    @Inject SsarAuthorizer ssarAuthorizer;

    private Set<String> agentPermissions;

    @PostConstruct
    void initAgentPermissions() {
        agentPermissions =
                config.agentPermissions().orElse(List.of()).stream()
                        .map(StringUtils::strip)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toUnmodifiableSet());
        log.debugf("Agent gateway requests will be granted permissions %s", agentPermissions);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Resolved unconditionally and before the mode is consulted: this is what strips an agent
        // stamp smuggled alongside oauth-proxy identity headers, and skipping it in any mode would
        // skip that.
        ProvenancePath path = provenance.resolve(context);

        if (config.mode() == RbacMode.PERMISSIVE) {
            return Uni.createFrom()
                    .item(
                            buildPermissiveIdentity(
                                    permissivePrincipal(context, path),
                                    path == ProvenancePath.AGENT));
        }

        return switch (path) {
            case AGENT -> {
                log.debug("Agent gateway stamp verified, granting agent identity");
                yield Uni.createFrom().item(buildAgentIdentity());
            }
            case UNTRUSTED -> {
                log.debug("Request carries neither provenance stamp, returning null");
                yield Uni.createFrom().nullItem();
            }
            case USER -> authenticateUser(context);
        };
    }

    /** Applies the configured mode to a request established as having come from the user path. */
    private Uni<SecurityIdentity> authenticateUser(RoutingContext context) {
        return switch (config.mode()) {
            case BASIC -> {
                String user = context.request().getHeader(ProxyHeaders.FORWARDED_USER);
                if (StringUtils.isBlank(user)) {
                    log.debug("BASIC mode: no X-Forwarded-User header, returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("BASIC mode: authenticated user %s", user);
                yield Uni.createFrom().item(buildPermissiveIdentity(user, false));
            }
            case OPENSHIFT -> {
                String user = context.request().getHeader(ProxyHeaders.FORWARDED_USER);
                String token = SsarAuthorizer.extractAccessToken(context);
                if (StringUtils.isBlank(user) || StringUtils.isBlank(token)) {
                    log.debug("OPENSHIFT mode: missing user or access token, returning null");
                    yield Uni.createFrom().nullItem();
                }
                log.debugf("OPENSHIFT mode: authenticated user %s", user);
                context.put(SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN, token);
                yield Uni.createFrom().item(ssarAuthorizer.buildIdentity(user, token));
            }
            default -> Uni.createFrom().nullItem();
        };
    }

    /**
     * Names the unrestricted PERMISSIVE identity. A gateway-forwarded request is named {@link
     * #AGENT_PRINCIPAL}, because the gateway clears {@link ProxyHeaders#FORWARDED_USER} and such a
     * request would otherwise have no principal at all. This affects attribution only: the identity
     * returned is unrestricted either way, which is why the agent permission set has no effect in
     * this mode.
     */
    private static String permissivePrincipal(RoutingContext context, ProvenancePath path) {
        if (path == ProvenancePath.AGENT) {
            return AGENT_PRINCIPAL;
        }
        String user = context.request().getHeader(ProxyHeaders.FORWARDED_USER);
        return StringUtils.isBlank(user) ? "" : user;
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
     *
     * @param agent whether the request was established as {@link ProvenancePath#AGENT}, recorded as
     *     {@link #AGENT_IDENTITY_ATTRIBUTE} so that the gateway remains distinguishable from a user
     *     who happens to be named {@link #AGENT_PRINCIPAL}.
     */
    private static SecurityIdentity buildPermissiveIdentity(String user, boolean agent) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user))
                .setAnonymous(false)
                .addAttribute(AGENT_IDENTITY_ATTRIBUTE, agent)
                .addPermissionChecker(permission -> Uni.createFrom().item(true))
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
                .addAttribute(AGENT_IDENTITY_ATTRIBUTE, true)
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
}
