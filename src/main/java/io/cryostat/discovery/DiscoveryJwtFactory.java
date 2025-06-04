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
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

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
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DiscoveryJwtFactory {

    public static final String RESOURCE_CLAIM = "resource";
    public static final String REALM_CLAIM = "realm";
    static final String DISCOVERY_API_PATH = "/api/v4/discovery/";

    @ConfigProperty(name = "cryostat.discovery.plugins.ping-period")
    Duration discoveryPingPeriod;

    @ConfigProperty(name = "cryostat.discovery.plugins.jwt.signature.algorithm")
    String signatureAlgorithm;

    @ConfigProperty(name = "cryostat.discovery.plugins.jwt.encryption.algorithm")
    String encryptionAlgorithm;

    @ConfigProperty(name = "cryostat.discovery.plugins.jwt.encryption.method")
    String encryptionMethod;

    @ConfigProperty(name = "cryostat.http.proxy.tls-enabled")
    boolean tlsEnabled;

    @ConfigProperty(name = "cryostat.http.proxy.host")
    String httpHost;

    @ConfigProperty(name = "cryostat.http.proxy.port")
    int httpPort;

    @ConfigProperty(name = "cryostat.http.proxy.path")
    String httpPath;

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final JWEEncrypter encrypter;
    private final JWEDecrypter decrypter;

    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW")
    DiscoveryJwtFactory(
            @ConfigProperty(name = "cryostat.discovery.plugins.jwt.secret.algorithm")
                    String secretKeyAlgorithm,
            @ConfigProperty(name = "cryostat.discovery.plugins.jwt.secret.keysize")
                    int secretKeySize) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(secretKeyAlgorithm);
            generator.init(secretKeySize);
            SecretKey secretKey = generator.generateKey();
            this.signer = new MACSigner(secretKey);
            this.verifier = new MACVerifier(secretKey);
            this.encrypter = new DirectEncrypter(secretKey);
            this.decrypter = new DirectDecrypter(secretKey);
        } catch (NoSuchAlgorithmException | JOSEException e) {
            throw new RuntimeException(e);
        }
    }

    public String createDiscoveryPluginJwt(
            DiscoveryPlugin plugin, InetAddress requestAddr, URI resource)
            throws SocketException, UnknownHostException, URISyntaxException, JOSEException {
        URI hostUri =
                new URI(
                        String.format(
                                "%s://%s:%d%s",
                                tlsEnabled ? "https" : "http", httpHost, httpPort, httpPath));
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
                        .subject(plugin.id.toString())
                        .claim(RESOURCE_CLAIM, resource.toASCIIString())
                        .claim(REALM_CLAIM, plugin.realm.name)
                        .build();

        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.parse(signatureAlgorithm)).build(),
                        claims);
        jwt.sign(signer);

        JWEHeader header =
                new JWEHeader.Builder(
                                JWEAlgorithm.parse(encryptionAlgorithm),
                                EncryptionMethod.parse(encryptionMethod))
                        .contentType("JWT")
                        .build();
        JWEObject jwe = new JWEObject(header, new Payload(jwt));
        jwe.encrypt(encrypter);

        return jwe.serialize();
    }

    public JWT parseDiscoveryPluginJwt(
            DiscoveryPlugin plugin, String rawToken, URI resource, InetAddress requestAddr)
            throws ParseException,
                    JOSEException,
                    BadJWTException,
                    SocketException,
                    UnknownHostException,
                    URISyntaxException {
        return parseDiscoveryPluginJwt(plugin, rawToken, resource, requestAddr, true);
    }

    public JWT parseDiscoveryPluginJwt(
            DiscoveryPlugin plugin,
            String rawToken,
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

        URI hostUri =
                new URI(
                                String.format(
                                        "%s://%s:%d/%s",
                                        tlsEnabled ? "https" : "http",
                                        httpHost,
                                        httpPort,
                                        httpPath))
                        .normalize();
        String issuer = hostUri.toString();
        JWTClaimsSet exactMatchClaims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(List.of(issuer, requestAddr.getHostAddress()))
                        .subject(plugin.id.toString())
                        .claim(RESOURCE_CLAIM, resource.toASCIIString())
                        .claim(REALM_CLAIM, plugin.realm.name)
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

    public URI getPluginLocation(DiscoveryPlugin plugin) throws URISyntaxException {
        URI hostUri =
                new URI(
                                String.format(
                                        "%s://%s:%d/",
                                        tlsEnabled ? "https" : "http", httpHost, httpPort))
                        .resolve(httpPath)
                        .resolve(DISCOVERY_API_PATH)
                        .normalize();
        return hostUri.resolve(plugin.id.toString()).normalize();
    }
}
