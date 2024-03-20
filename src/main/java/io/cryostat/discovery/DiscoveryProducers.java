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

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

public class DiscoveryProducers {

    @Produces
    @ApplicationScoped
    static SecretKey provideSecretKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Produces
    @ApplicationScoped
    static JWSSigner provideJwsSigner(SecretKey key) {
        try {
            return new MACSigner(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Produces
    @ApplicationScoped
    static JWSVerifier provideJwsVerifier(SecretKey key) {
        try {
            return new MACVerifier(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Produces
    @ApplicationScoped
    static JWEEncrypter provideJweEncrypter(SecretKey key) {
        try {
            return new DirectEncrypter(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Produces
    @ApplicationScoped
    static JWEDecrypter provideJweDecrypter(SecretKey key) {
        try {
            return new DirectDecrypter(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
