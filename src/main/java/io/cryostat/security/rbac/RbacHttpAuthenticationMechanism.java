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

import java.util.Collections;
import java.util.Set;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
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
 *       X-Forwarded-Access-Token}; stores the raw token in identity attributes for use by {@link
 *       RbacSecurityIdentityAugmentor}; returns {@code null} if either header is absent.
 * </ul>
 */
@ApplicationScoped
public class RbacHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    static final String HEADER_FORWARDED_USER = "X-Forwarded-User";
    static final String HEADER_FORWARDED_TOKEN = "X-Forwarded-Access-Token";

    /**
     * All fine-grained permission names declared via {@code @PermissionsAllowed} across all REST
     * resources. Used to pre-populate PERMISSIVE-mode identities so that the built-in Quarkus
     * permission check passes without needing an augmentor.
     */
    static final Set<String> ALL_PERMISSIONS =
            Set.of(
                    "targets:read",
                    "targets:write",
                    "targets:delete",
                    "discoverynodes:read",
                    "discoverynodes:write",
                    "activerecordings:read",
                    "activerecordings:write",
                    "activerecordings:delete",
                    "archivedrecordings:read",
                    "archivedrecordings:write",
                    "archivedrecordings:delete",
                    "reports:read",
                    "reports:write",
                    "credentials:read",
                    "credentials:write",
                    "credentials:delete",
                    "rules:read",
                    "rules:write",
                    "rules:delete",
                    "eventtemplates:read",
                    "eventtemplates:write",
                    "eventtemplates:delete",
                    "events:read",
                    "matchexpressions:read",
                    "smarttriggers:read",
                    "smarttriggers:write",
                    "smarttriggers:delete",
                    "asyncprofiler:read",
                    "asyncprofiler:write",
                    "asyncprofiler:delete",
                    "diagnostics:write",
                    "heapdumps:read",
                    "heapdumps:write",
                    "heapdumps:delete",
                    "threaddumps:read",
                    "threaddumps:write",
                    "threaddumps:delete",
                    "unifiedlogs:read",
                    "unifiedlogs:write",
                    "unifiedlogs:delete");

    private static final Logger LOG = Logger.getLogger(RbacHttpAuthenticationMechanism.class);

    @Inject RbacConfig config;

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
                    LOG.debug("BASIC mode: no X-Forwarded-User header, returning null");
                    yield Uni.createFrom().nullItem();
                }
                LOG.debugf("BASIC mode: authenticated user %s", user);
                yield Uni.createFrom().item(buildIdentity(user, null));
            }
            case OPENSHIFT -> {
                String user = context.request().getHeader(HEADER_FORWARDED_USER);
                String token = context.request().getHeader(HEADER_FORWARDED_TOKEN);
                if (StringUtils.isBlank(user) || StringUtils.isBlank(token)) {
                    LOG.debug(
                            "OPENSHIFT mode: missing X-Forwarded-User or X-Forwarded-Access-Token,"
                                    + " returning null");
                    yield Uni.createFrom().nullItem();
                }
                LOG.debugf("OPENSHIFT mode: authenticated user %s", user);
                yield Uni.createFrom().item(buildIdentity(user, token));
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
     * Builds an identity with all known permissions pre-granted. Used in PERMISSIVE mode where no
     * real authorization check is desired.
     */
    private static SecurityIdentity buildPermissiveIdentity(String user) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user))
                .setAnonymous(false)
                .addPermissionsAsString(ALL_PERMISSIONS)
                .build();
    }

    private static SecurityIdentity buildIdentity(String user, String rawToken) {
        var builder =
                QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(user))
                        .setAnonymous(false);
        if (StringUtils.isNotBlank(rawToken)) {
            builder.addAttribute(
                    RbacSecurityIdentityAugmentor.RAW_ACCESS_TOKEN_ATTRIBUTE, rawToken);
        }
        return builder.build();
    }
}
