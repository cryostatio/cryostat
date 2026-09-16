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
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

import io.cryostat.ConfigProperties;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Decides which of Cryostat's two inbound proxy paths a request arrived through, from the
 * provenance stamps the proxies apply and from the secrets this deployment is configured with.
 *
 * <p>Each path proves itself by <em>possession</em> of a shared secret rather than by the presence
 * of a header or by its peer address, neither of which can distinguish the two proxies from each
 * other or from any other in-pod caller. The agent gateway stamps {@link ProxyHeaders#AGENT_AUTH}
 * with {@link ConfigProperties#AGENT_GATEWAY_SECRET}; the user-path auth-strip proxy stamps {@link
 * ProxyHeaders#USER_PROXY_AUTH} with {@link ConfigProperties#USER_PROXY_SECRET}. Each proxy clears
 * the other's stamp, so a stamp presented on the wrong path is inert.
 *
 * <p>The two unconfigured cases fail in deliberately opposite directions. With no agent gateway
 * secret the Agent principal is simply unreachable: fail closed. With no user proxy secret,
 * user-path provenance falls back to inference: the posture of deployments with a single entrypoint
 * and no auth-strip hop.
 *
 * <p>This class deliberately knows nothing about identity, permissions, or {@link RbacMode}. It
 * answers only "which hop forwarded this".
 *
 * <p>A plain digest rather than a password KDF is correct here: the secrets are 32 characters drawn
 * uniformly from a 64-symbol alphabet, so there is no guessing or rainbow-table exposure to stretch
 * against, and a deliberately slow hash would be run on every inbound request.
 */
@Singleton
public class RequestProvenance {

    private static final Logger log = Logger.getLogger(RequestProvenance.class);
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final byte[] gatewaySecretDigest;
    private final byte[] userProxySecretDigest;

    @Inject
    RequestProvenance(
            @ConfigProperty(name = ConfigProperties.AGENT_GATEWAY_SECRET)
                    Optional<String> agentGatewaySecret,
            @ConfigProperty(name = ConfigProperties.USER_PROXY_SECRET)
                    Optional<String> userProxySecret) {
        this.gatewaySecretDigest = digestOrNull(agentGatewaySecret);
        this.userProxySecretDigest = digestOrNull(userProxySecret);

        if (gatewaySecretDigest == null) {
            log.warn("No agent gateway secret configured; Agent principal will not be granted");
        }
        if (userProxySecretDigest == null) {
            log.warn(
                    "No user proxy secret configured; user-path provenance will be inferred from"
                            + " the absence of an agent stamp rather than proven");
        }
    }

    /**
     * Rejects a deployment that configures both paths with the same secret. The two stamps would
     * then be interchangeable, and since the agent stamp is tested first, every user-path request
     * would resolve as {@link ProvenancePath#AGENT}. Nothing on the request can distinguish that
     * case, so it has to be caught at startup rather than per request.
     *
     * <p>Observing {@link StartupEvent} is also what forces this otherwise lazily-created bean to
     * be instantiated during boot, so a misconfiguration fails startup rather than the first
     * request.
     */
    void onStart(@Observes StartupEvent evt) {
        if (gatewaySecretDigest != null
                && userProxySecretDigest != null
                && MessageDigest.isEqual(gatewaySecretDigest, userProxySecretDigest)) {
            throw new IllegalStateException(
                    String.format(
                            "%s and %s must not be configured with the same value; the two proxy"
                                    + " paths would be indistinguishable",
                            ConfigProperties.AGENT_GATEWAY_SECRET,
                            ConfigProperties.USER_PROXY_SECRET));
        }
    }

    /**
     * Classifies the request, first removing any agent stamp that arrived alongside oauth-proxy
     * identity headers.
     *
     * <p>Callers should invoke this exactly once per request and on every request, including in
     * modes that go on to ignore the result, so that the sanitization below is never skipped.
     */
    public ProvenancePath resolve(RoutingContext context) {
        sanitize(context);
        if (isStampedBy(context, ProxyHeaders.AGENT_AUTH, gatewaySecretDigest)) {
            return ProvenancePath.AGENT;
        }
        if (userProxySecretDigest == null) {
            // No auth-strip hop in this deployment, so no stamp can exist to look for.
            return ProvenancePath.USER;
        }
        if (isStampedBy(context, ProxyHeaders.USER_PROXY_AUTH, userProxySecretDigest)) {
            return ProvenancePath.USER;
        }
        return ProvenancePath.UNTRUSTED;
    }

    /**
     * Removes the agent provenance stamp from a request that also carries oauth-proxy identity
     * headers. The two are mutually exclusive by construction: the gateway clears {@code
     * X-Forwarded-*} and the auth-strip proxy clears {@link ProxyHeaders#AGENT_AUTH}, so seeing
     * both means one of those hops was bypassed. Log it as an anomaly.
     */
    private void sanitize(RoutingContext context) {
        if (StringUtils.isNotBlank(context.request().getHeader(ProxyHeaders.FORWARDED_USER))
                && StringUtils.isNotBlank(context.request().getHeader(ProxyHeaders.AGENT_AUTH))) {
            log.warn(
                    "Request carries both oauth-proxy and agent gateway headers; stripping agent"
                            + " headers");
            context.request().headers().remove(ProxyHeaders.AGENT_AUTH);
        }
    }

    /**
     * Constant-time check that the named header carries the secret behind {@code expectedDigest}.
     * Possession is the whole test: a forged, smuggled, or hand-curled header without the secret is
     * inert, and so is a header cleared to the empty string by an upstream proxy.
     *
     * @param expectedDigest the digest of the expected secret, or {@code null} when no secret is
     *     configured, in which case no request can ever match
     */
    private static boolean isStampedBy(
            RoutingContext context, String header, byte[] expectedDigest) {
        if (expectedDigest == null) {
            return false;
        }
        String presented = context.request().getHeader(header);
        if (StringUtils.isBlank(presented)) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.isEqual(digest(presentedBytes), expectedDigest);
        } finally {
            Arrays.fill(presentedBytes, (byte) 0);
        }
    }

    private static byte[] digestOrNull(Optional<String> secret) {
        return secret.filter(StringUtils::isNotBlank)
                .map(
                        s -> {
                            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                            try {
                                return digest(bytes);
                            } finally {
                                Arrays.fill(bytes, (byte) 0);
                            }
                        })
                .orElse(null);
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " not available", e);
        }
    }
}
