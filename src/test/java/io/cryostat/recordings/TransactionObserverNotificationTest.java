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
package io.cryostat.recordings;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class TransactionObserverNotificationTest extends AbstractTransactionalTestBase {

    @Inject TestTransactionEventService eventService;
    @Inject TestTransactionObserver observer;

    @Test
    void testAfterSuccessObserverRunsAfterCommit() throws Exception {
        observer.reset();

        String marker = eventService.fireInCommittedTransaction("commit-marker");

        TestTransactionObserver.Observation observation =
                observer.await(marker, Duration.ofSeconds(5));

        assertThat(observation, notNullValue());
        assertThat(observation.marker(), equalTo("commit-marker"));
        assertThat(observation.persistedValue(), equalTo("commit-marker"));
    }

    @Test
    void testAfterSuccessObserverNotRunOnRollback() {
        observer.reset();

        assertThrows(RuntimeException.class, () -> eventService.fireAndRollback("rollback-marker"));

        assertThrows(
                TimeoutException.class,
                () -> observer.await("rollback-marker", Duration.ofSeconds(2)));

        long count =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        Long.valueOf(
                                                TransactionObserverMarker.count(
                                                        "marker = ?1", "rollback-marker")));
        assertThat(count, equalTo(0L));
    }

    @Test
    void testAfterSuccessObserverSeesCommittedDatabaseState() throws Exception {
        observer.reset();

        String marker = eventService.fireInCommittedTransaction("committed-state");

        TestTransactionObserver.Observation observation =
                observer.await(marker, Duration.ofSeconds(5));

        assertThat(observation.persistedValue(), equalTo("committed-state"));
    }

    @Test
    void testDeleteEventFiresAfterCommit() throws Exception {
        observer.reset();

        String marker = eventService.createAndDeleteInCommittedTransaction("delete-marker");

        TestTransactionObserver.Observation observation =
                observer.await(marker, Duration.ofSeconds(5));

        assertThat(observation, notNullValue());
        assertThat(observation.marker(), equalTo("delete-marker"));
        assertThat(observation.persistedValue(), equalTo(null));
    }

    @Test
    void testDeleteEventNotFiredOnRollback() {
        observer.reset();

        assertThrows(
                RuntimeException.class,
                () -> eventService.createDeleteAndRollback("delete-rollback-marker"));

        assertThrows(
                TimeoutException.class,
                () -> observer.await("delete-rollback-marker", Duration.ofSeconds(2)));

        long count =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        Long.valueOf(
                                                TransactionObserverMarker.count(
                                                        "marker = ?1", "delete-rollback-marker")));
        assertThat(count, equalTo(0L));
    }

    @Test
    void testDeleteEventPayloadContainsPreCommitData() throws Exception {
        observer.reset();

        String marker = eventService.createAndDeleteInCommittedTransaction("delete-payload-marker");

        TestTransactionObserver.Observation observation =
                observer.await(marker, Duration.ofSeconds(5));

        assertThat(observation, notNullValue());
        assertThat(observation.marker(), equalTo("delete-payload-marker"));
        assertThat(observation.persistedValue(), equalTo(null));
    }

    @ApplicationScoped
    static class TestTransactionEventService {

        @Inject Event<TestTransactionEvent> events;

        @Transactional
        public String fireInCommittedTransaction(String marker) {
            TransactionObserverMarker entity = new TransactionObserverMarker();
            entity.marker = marker;
            entity.persist();
            events.fire(new TestTransactionEvent(marker));
            return marker;
        }

        @Transactional
        public void fireAndRollback(String marker) {
            TransactionObserverMarker entity = new TransactionObserverMarker();
            entity.marker = marker;
            entity.persist();
            events.fire(new TestTransactionEvent(marker));
            throw new RuntimeException("force rollback");
        }

        @Transactional
        public String createAndDeleteInCommittedTransaction(String marker) {
            TransactionObserverMarker entity = new TransactionObserverMarker();
            entity.marker = marker;
            entity.persist();
            entity.delete();
            events.fire(new TestTransactionEvent(marker));
            return marker;
        }

        @Transactional
        public void createDeleteAndRollback(String marker) {
            TransactionObserverMarker entity = new TransactionObserverMarker();
            entity.marker = marker;
            entity.persist();
            entity.delete();
            events.fire(new TestTransactionEvent(marker));
            throw new RuntimeException("force rollback");
        }
    }

    @ApplicationScoped
    static class TestTransactionObserver {

        private volatile CompletableFuture<Observation> future = new CompletableFuture<>();

        void onEvent(
                @Observes(during = TransactionPhase.AFTER_SUCCESS) TestTransactionEvent event) {
            try {
                String committedValue =
                        QuarkusTransaction.requiringNew()
                                .call(
                                        () ->
                                                TransactionObserverMarker.find(
                                                                "marker", event.marker)
                                                        .firstResultOptional()
                                                        .map(TransactionObserverMarker.class::cast)
                                                        .map(e -> e.marker)
                                                        .orElse(null));
                future.complete(new Observation(event.marker, committedValue));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }

        void reset() {
            future = new CompletableFuture<>();
        }

        Observation await(String marker, Duration timeout) throws Exception {
            Observation observation = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!marker.equals(observation.marker())) {
                throw new TimeoutException("Observed unexpected marker " + observation.marker());
            }
            return observation;
        }

        record Observation(String marker, String persistedValue) {}
    }

    static class TestTransactionEvent {
        final String marker;

        TestTransactionEvent(String marker) {
            this.marker = marker;
        }
    }
}
