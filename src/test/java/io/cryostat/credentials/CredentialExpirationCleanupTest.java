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
package io.cryostat.credentials;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.time.Instant;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.discovery.DiscoveryPlugin;
import io.cryostat.discovery.NodeType;
import io.cryostat.expressions.MatchExpression;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class CredentialExpirationCleanupTest extends AbstractTransactionalTestBase {

    @Inject CredentialExpirationJob credentialExpirationJob;

    @Test
    void deletesExpiredUnassociatedCredentials() {
        long credentialId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    var expr = new MatchExpression("true");
                                    expr.persist();

                                    var credential = new Credential();
                                    credential.matchExpression = expr;
                                    credential.username = "user";
                                    credential.password = "pass";
                                    credential.expiresAt = Instant.now().minusSeconds(10);
                                    credential.persist();

                                    return credential.id;
                                });

        credentialExpirationJob.cleanupExpiredCredentials();

        var deleted =
                QuarkusTransaction.requiringNew()
                        .call(() -> Credential.<Credential>findById(credentialId));
        assertNull(deleted);
    }

    @Test
    void doesNotDeleteExpiredCredentialsStillAssociatedWithPlugin() {
        long credentialId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    var expr = new MatchExpression("true");
                                    expr.persist();

                                    var credential = new Credential();
                                    credential.matchExpression = expr;
                                    credential.username = "user";
                                    credential.password = "pass";
                                    credential.expiresAt = Instant.now().minusSeconds(10);
                                    credential.persist();

                                    var realm = new DiscoveryNode();
                                    realm.name = "cleanup_test_realm";
                                    realm.nodeType = NodeType.BaseNodeType.REALM.getKind();
                                    realm.persist();

                                    var plugin = new DiscoveryPlugin();
                                    plugin.realm = realm;
                                    plugin.callback =
                                            URI.create("http://localhost:8081/health/liveness");
                                    plugin.builtin = false;
                                    plugin.credential = credential;
                                    plugin.persist();

                                    return credential.id;
                                });

        credentialExpirationJob.cleanupExpiredCredentials();

        var retained =
                QuarkusTransaction.requiringNew()
                        .call(() -> Credential.<Credential>findById(credentialId));
        assertNotNull(retained);
    }
}
