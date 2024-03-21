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
package io.cryostat.discovery;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.Objects;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.proc.BadJWTException;
import io.quarkus.security.UnauthorizedException;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DiscoveryJwtValidator {

    @ConfigProperty(name = "cryostat.http.proxy.host")
    String httpHost;

    @ConfigProperty(name = "cryostat.http.proxy.port")
    int httpPort;

    @Inject DiscoveryJwtFactory jwtFactory;
    @Inject Logger logger;

    public JWT validateJwt(
            RoutingContext ctx, DiscoveryPlugin plugin, String token, boolean validateTimeClaims)
            throws ParseException,
                    JOSEException,
                    SocketException,
                    UnknownHostException,
                    URISyntaxException,
                    MalformedURLException {
        if (StringUtils.isBlank(token)) {
            throw new UnauthorizedException();
        }
        InetAddress addr = null;
        HttpServerRequest req = ctx.request();
        if (req.remoteAddress() != null) {
            addr = Discovery.tryResolveAddress(addr, req.remoteAddress().host());
        }
        MultiMap headers = req.headers();
        addr = Discovery.tryResolveAddress(addr, headers.get(Discovery.X_FORWARDED_FOR));

        URI hostUri = new URI(String.format("http://%s:%d/", httpHost, httpPort));

        JWT parsed;
        try {
            parsed =
                    jwtFactory.parseDiscoveryPluginJwt(
                            token,
                            plugin.realm.name,
                            jwtFactory.getPluginLocation(
                                    "/api/v2.2/discovery", plugin.id.toString()),
                            addr,
                            validateTimeClaims);
        } catch (BadJWTException e) {
            throw new UnauthorizedException(e);
        }

        URI requestUri = new URI(req.absoluteURI());
        URI fullRequestUri =
                new URI(hostUri.getScheme(), hostUri.getAuthority(), null, null, null)
                        .resolve(requestUri.getRawPath());
        URI relativeRequestUri = new URI(requestUri.getRawPath());
        URI resourceClaim;
        try {
            resourceClaim =
                    new URI(
                            parsed.getJWTClaimsSet()
                                    .getStringClaim(DiscoveryJwtFactory.RESOURCE_CLAIM));
        } catch (URISyntaxException use) {
            throw new UnauthorizedException(use);
        }
        logger.infov(
                "JWT resource claim: {0} comparing to - fullRequestUri: {1} relativeRequestUri:"
                        + " {2}",
                resourceClaim, fullRequestUri, relativeRequestUri);
        boolean matchesAbsoluteRequestUri =
                resourceClaim.isAbsolute() && Objects.equals(fullRequestUri, resourceClaim);
        boolean matchesRelativeRequestUri = Objects.equals(relativeRequestUri, resourceClaim);
        if (!matchesAbsoluteRequestUri && !matchesRelativeRequestUri) {
            throw new UnauthorizedException(
                    "Token resource claim does not match requested resource");
        }

        // try {
        //     String subject = parsed.getJWTClaimsSet().getSubject();
        //     if (!auth.validateHttpHeader(() -> subject, resourceActions()).get()) {
        //         throw new ApiException(401, "Token subject has insufficient permissions");
        //     }
        // } catch (ExecutionException | InterruptedException e) {
        //     throw new ApiException(401, "Token subject permissions could not be determined");
        // }

        return parsed;
    }
}
