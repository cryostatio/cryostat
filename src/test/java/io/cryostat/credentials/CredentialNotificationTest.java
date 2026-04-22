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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.credentials.events.CredentialEvents;
import io.cryostat.expressions.MatchExpression;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Credential entity dirty-field filtering implementation. Verifies that only relevant
 * field updates trigger notifications.
 *
 * <p>The Credential entity uses the NotificationDirtyCheckSupport to filter out updates to fields
 * marked with @JsonIgnore or @JsonProperty(access = WRITE_ONLY). This prevents unnecessary
 * notifications when only internal tracking fields like expiresAt or lastUsedAt are updated.
 */
@QuarkusTest
class CredentialNotificationTest extends AbstractTransactionalTestBase {

    @Inject CredentialTestService credentialService;
    @Inject CredentialEventObserver observer;

    @BeforeEach
    void resetObserver() {
        observer.reset();
    }

    @Test
    void testIgnoredFieldUpdateSuppressed_expiresAt() {
        Long credentialId = credentialService.createCredential("target.alias == 'test'");

        observer.reset();

        credentialService.updateExpiresAt(credentialId, Instant.now().plusSeconds(3600));

        assertThrows(
                TimeoutException.class,
                () -> observer.awaitUpdate(credentialId, Duration.ofSeconds(2)),
                "Update notification should not be fired when only expiresAt is changed");
    }

    @Test
    void testIgnoredFieldUpdateSuppressed_lastUsedAt() {
        Long credentialId = credentialService.createCredential("target.alias == 'test'");

        observer.reset();

        credentialService.updateLastUsedAt(credentialId, Instant.now());

        assertThrows(
                TimeoutException.class,
                () -> observer.awaitUpdate(credentialId, Duration.ofSeconds(2)),
                "Update notification should not be fired when only lastUsedAt is changed");
    }

    @Test
    void testMultipleIgnoredFieldUpdatesStillSuppressed() {
        Long credentialId = credentialService.createCredential("target.alias == 'test'");

        observer.reset();

        credentialService.updateExpiresAtAndLastUsedAt(
                credentialId, Instant.now().plusSeconds(3600), Instant.now());

        assertThrows(
                TimeoutException.class,
                () -> observer.awaitUpdate(credentialId, Duration.ofSeconds(2)),
                "Update notification should not be fired when only ignored fields are changed");
    }

    @Test
    void testCreateNotificationUnchanged() throws Exception {
        observer.reset();

        Long credentialId = credentialService.createCredential("target.alias == 'create-test'");

        CredentialEventObserver.EventCapture capture =
                observer.awaitCreate(credentialId, Duration.ofSeconds(5));

        assertThat(capture, notNullValue());
        assertThat(capture.credentialId(), equalTo(credentialId));
        assertThat(capture.eventType(), equalTo("CREATE"));
    }

    @Test
    void testDeleteNotificationUnchanged() throws Exception {
        Long credentialId = credentialService.createCredential("target.alias == 'delete-test'");

        observer.reset();

        credentialService.deleteCredential(credentialId);

        CredentialEventObserver.EventCapture capture =
                observer.awaitDelete(credentialId, Duration.ofSeconds(5));

        assertThat(capture, notNullValue());
        assertThat(capture.credentialId(), equalTo(credentialId));
        assertThat(capture.eventType(), equalTo("DELETE"));
    }

    @Test
    void testUpdateNotificationNotFiredOnRollback() {
        Long credentialId = credentialService.createCredential("target.alias == 'test'");

        observer.reset();

        assertThrows(
                RuntimeException.class,
                () ->
                        credentialService.updateExpiresAtAndRollback(
                                credentialId, Instant.now().plusSeconds(3600)));

        assertThrows(
                TimeoutException.class,
                () -> observer.awaitUpdate(credentialId, Duration.ofSeconds(2)),
                "Update notification should not be fired when transaction is rolled back");

        Instant expiresAt =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    Credential credential = Credential.findById(credentialId);
                                    return credential.expiresAt;
                                });
        assertThat("expiresAt should not have changed after rollback", expiresAt, equalTo(null));
    }

    @ApplicationScoped
    static class CredentialTestService {

        @Transactional
        public Long createCredential(String matchExpressionScript) {
            MatchExpression matchExpression = new MatchExpression(matchExpressionScript);
            matchExpression.persist();

            Credential credential = new Credential();
            credential.matchExpression = matchExpression;
            credential.username = "testuser";
            credential.password = "testpass";
            credential.persist();

            return credential.id;
        }

        @Transactional
        public void updateExpiresAt(Long credentialId, Instant expiresAt) {
            Credential credential = Credential.findById(credentialId);
            if (credential == null) {
                throw new IllegalArgumentException("Credential not found: " + credentialId);
            }

            credential.expiresAt = expiresAt;
            credential.persist();
        }

        @Transactional
        public void updateLastUsedAt(Long credentialId, Instant lastUsedAt) {
            Credential credential = Credential.findById(credentialId);
            if (credential == null) {
                throw new IllegalArgumentException("Credential not found: " + credentialId);
            }

            credential.lastUsedAt = lastUsedAt;
            credential.persist();
        }

        @Transactional
        public void updateExpiresAtAndLastUsedAt(
                Long credentialId, Instant expiresAt, Instant lastUsedAt) {
            Credential credential = Credential.findById(credentialId);
            if (credential == null) {
                throw new IllegalArgumentException("Credential not found: " + credentialId);
            }

            credential.expiresAt = expiresAt;
            credential.lastUsedAt = lastUsedAt;
            credential.persist();
        }

        @Transactional
        public void deleteCredential(Long credentialId) {
            Credential credential = Credential.findById(credentialId);
            if (credential == null) {
                throw new IllegalArgumentException("Credential not found: " + credentialId);
            }

            credential.delete();
        }

        @Transactional
        public void updateExpiresAtAndRollback(Long credentialId, Instant expiresAt) {
            Credential credential = Credential.findById(credentialId);
            if (credential == null) {
                throw new IllegalArgumentException("Credential not found: " + credentialId);
            }

            credential.expiresAt = expiresAt;
            credential.persist();

            throw new RuntimeException("force rollback");
        }
    }

    @ApplicationScoped
    static class CredentialEventObserver {

        private volatile CompletableFuture<EventCapture> createFuture = new CompletableFuture<>();
        private volatile CompletableFuture<EventCapture> updateFuture = new CompletableFuture<>();
        private volatile CompletableFuture<EventCapture> deleteFuture = new CompletableFuture<>();

        void onCredentialCreated(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        CredentialEvents.CredentialCreated event) {
            createFuture.complete(new EventCapture(event.getEntityId(), "CREATE"));
        }

        void onCredentialUpdated(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        CredentialEvents.CredentialUpdated event) {
            updateFuture.complete(new EventCapture(event.getEntityId(), "UPDATE"));
        }

        void onCredentialDeleted(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        CredentialEvents.CredentialDeleted event) {
            deleteFuture.complete(new EventCapture(event.getEntityId(), "DELETE"));
        }

        void reset() {
            createFuture = new CompletableFuture<>();
            updateFuture = new CompletableFuture<>();
            deleteFuture = new CompletableFuture<>();
        }

        EventCapture awaitCreate(Long expectedId, Duration timeout) throws Exception {
            EventCapture capture = createFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!expectedId.equals(capture.credentialId())) {
                throw new TimeoutException(
                        "Observed unexpected credential ID " + capture.credentialId());
            }
            return capture;
        }

        EventCapture awaitUpdate(Long expectedId, Duration timeout) throws Exception {
            EventCapture capture = updateFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!expectedId.equals(capture.credentialId())) {
                throw new TimeoutException(
                        "Observed unexpected credential ID " + capture.credentialId());
            }
            return capture;
        }

        EventCapture awaitDelete(Long expectedId, Duration timeout) throws Exception {
            EventCapture capture = deleteFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!expectedId.equals(capture.credentialId())) {
                throw new TimeoutException(
                        "Observed unexpected credential ID " + capture.credentialId());
            }
            return capture;
        }

        record EventCapture(Long credentialId, String eventType) {}
    }
}
