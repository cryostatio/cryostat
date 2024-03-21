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

import java.security.NoSuchAlgorithmException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

public class DiscoveryProducers {

    @Produces
    @ApplicationScoped
    static SecretKey provideSecretKey(
            @ConfigProperty(name = "cryostat.discovery.plugins.jwt.algorithm") String alg,
            @ConfigProperty(name = "cryostat.discovery.plugins.jwt.keysize") int keysize)
            throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance(alg);
        generator.init(keysize);
        return generator.generateKey();
    }

    @Produces
    @ApplicationScoped
    static JWSSigner provideJwsSigner(SecretKey key) throws KeyLengthException {
        return new MACSigner(key);
    }

    @Produces
    @ApplicationScoped
    static JWSVerifier provideJwsVerifier(SecretKey key) throws JOSEException {
        return new MACVerifier(key);
    }

    @Produces
    @ApplicationScoped
    static JWEEncrypter provideJweEncrypter(SecretKey key) throws KeyLengthException {
        return new DirectEncrypter(key);
    }

    @Produces
    @ApplicationScoped
    static JWEDecrypter provideJweDecrypter(SecretKey key) throws KeyLengthException {
        return new DirectDecrypter(key);
    }
}
