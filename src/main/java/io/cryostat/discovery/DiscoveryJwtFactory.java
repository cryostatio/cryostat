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
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DiscoveryJwtFactory {

    public static final String RESOURCE_CLAIM = "resource";
    public static final String REALM_CLAIM = "realm";

    @Inject JWSSigner signer;
    @Inject JWSVerifier verifier;
    @Inject JWEEncrypter encrypter;
    @Inject JWEDecrypter decrypter;

    @ConfigProperty(name = "cryostat.discovery.plugins.ping-period")
    Duration discoveryPingPeriod;

    @ConfigProperty(name = "cryostat.http.proxy.host")
    String httpHost;

    @ConfigProperty(name = "cryostat.http.proxy.port")
    int httpPort;

    public String createDiscoveryPluginJwt(
            String authzHeader, String realm, InetAddress requestAddr, URI resource)
            throws SocketException, UnknownHostException, URISyntaxException, JOSEException {
        URI hostUri = new URI(String.format("http://%s:%d/", httpHost, httpPort));
        String issuer = hostUri.toString();
        Date now = Date.from(Instant.now());
        Date expiry = Date.from(now.toInstant().plus(discoveryPingPeriod.multipliedBy(2)));
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(List.of(issuer, requestAddr.getHostAddress()))
                        .issueTime(now)
                        .notBeforeTime(now)
                        .expirationTime(expiry)
                        .subject(authzHeader)
                        .claim(RESOURCE_CLAIM, resource.toASCIIString())
                        .claim(REALM_CLAIM, realm)
                        .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(signer);

        JWEHeader header =
                new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                        .contentType("JWT")
                        .build();
        JWEObject jwe = new JWEObject(header, new Payload(jwt));
        jwe.encrypt(encrypter);

        return jwe.serialize();
    }

    public JWT parseDiscoveryPluginJwt(
            String rawToken, String realm, URI resource, InetAddress requestAddr)
            throws ParseException,
                    JOSEException,
                    BadJWTException,
                    SocketException,
                    UnknownHostException,
                    URISyntaxException {
        return parseDiscoveryPluginJwt(rawToken, realm, resource, requestAddr, true);
    }

    public JWT parseDiscoveryPluginJwt(
            String rawToken,
            String realm,
            URI resource,
            InetAddress requestAddr,
            boolean checkTimeClaims)
            throws ParseException,
                    JOSEException,
                    BadJWTException,
                    SocketException,
                    UnknownHostException,
                    URISyntaxException {
        JWEObject jwe = JWEObject.parse(rawToken);
        jwe.decrypt(decrypter);

        SignedJWT jwt = jwe.getPayload().toSignedJWT();
        jwt.verify(verifier);

        URI hostUri = new URI(String.format("http://%s:%d/", httpHost, httpPort));
        String issuer = hostUri.toString();
        JWTClaimsSet exactMatchClaims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(List.of(issuer, requestAddr.getHostAddress()))
                        .claim(RESOURCE_CLAIM, resource.toASCIIString())
                        .claim(REALM_CLAIM, realm)
                        .build();
        Set<String> requiredClaimNames =
                new HashSet<>(Set.of("iat", "iss", "aud", "sub", REALM_CLAIM));
        if (checkTimeClaims) {
            requiredClaimNames.add("exp");
            requiredClaimNames.add("nbf");
        }
        DefaultJWTClaimsVerifier<SecurityContext> verifier =
                new DefaultJWTClaimsVerifier<>(issuer, exactMatchClaims, requiredClaimNames);
        if (checkTimeClaims) {
            verifier.setMaxClockSkew(5);
        }
        verifier.verify(jwt.getJWTClaimsSet(), null);

        return jwt;
    }

    // TODO refactor this, inline into Discovery.java
    public URI getPluginLocation(String path, String pluginId) throws URISyntaxException {
        URI hostUri = new URI(String.format("http://%s:%d/%s/", httpHost, httpPort, path));
        return hostUri.resolve(pluginId).normalize();
    }
}
