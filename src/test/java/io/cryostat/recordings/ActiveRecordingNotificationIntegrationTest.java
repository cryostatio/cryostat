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
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.events.ActiveRecordingEvents;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class ActiveRecordingNotificationIntegrationTest extends AbstractTransactionalTestBase {

    @Inject TestEventCapture eventCapture;
    @Inject TestRecordingService recordingService;

    @BeforeEach
    void setup() {
        eventCapture.reset();
    }

    @Test
    void testActiveRecordingCreatedNotificationAfterCommit() throws Exception {
        Target target = createTestTarget();

        Long recordingId = recordingService.createRecording(target.id, "test-recording");

        ActiveRecordingEvents.ActiveRecordingCreated event =
                eventCapture.awaitCreatedEvent(recordingId, Duration.ofSeconds(5));

        assertThat(event, notNullValue());
        assertThat(event.getRecordingId(), equalTo(recordingId));
        assertThat(
                event.getCategory(),
                equalTo(ActiveRecordings.RecordingEventCategory.ACTIVE_CREATED));

        ActiveRecording recording =
                QuarkusTransaction.requiringNew().call(() -> ActiveRecording.findById(recordingId));
        assertThat(recording, notNullValue());
        assertThat(recording.name, equalTo("test-recording"));
    }

    @Test
    void testActiveRecordingStoppedNotificationAfterCommit() throws Exception {
        Target target = createTestTarget();
        Long recordingId = recordingService.createRecording(target.id, "test-stopped-recording");

        eventCapture.reset();

        recordingService.stopRecording(recordingId);

        ActiveRecordingEvents.ActiveRecordingStopped event =
                eventCapture.awaitStoppedEvent(recordingId, Duration.ofSeconds(5));

        assertThat(event, notNullValue());
        assertThat(event.getRecordingId(), equalTo(recordingId));
        assertThat(
                event.getCategory(),
                equalTo(ActiveRecordings.RecordingEventCategory.ACTIVE_STOPPED));

        ActiveRecording recording =
                QuarkusTransaction.requiringNew().call(() -> ActiveRecording.findById(recordingId));
        assertThat(recording, notNullValue());
        assertThat(recording.state, equalTo(RecordingState.STOPPED));
    }

    @Test
    void testActiveRecordingDeletedNotificationAfterCommit() throws Exception {
        Target target = createTestTarget();
        Long recordingId = recordingService.createRecording(target.id, "test-deleted-recording");

        eventCapture.reset();

        String recordingName =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    ActiveRecording rec = ActiveRecording.findById(recordingId);
                                    return rec.name;
                                });

        recordingService.deleteRecording(recordingId);

        ActiveRecordingEvents.ActiveRecordingDeleted event =
                eventCapture.awaitDeletedEvent(recordingId, Duration.ofSeconds(5));

        assertThat(event, notNullValue());
        assertThat(event.getRecordingId(), equalTo(recordingId));
        assertThat(
                event.getCategory(),
                equalTo(ActiveRecordings.RecordingEventCategory.ACTIVE_DELETED));
        assertThat(event.getPayload(), notNullValue());
        assertThat(event.getPayload().payload().recording().name(), equalTo(recordingName));

        ActiveRecording recording =
                QuarkusTransaction.requiringNew().call(() -> ActiveRecording.findById(recordingId));
        assertThat(recording, nullValue());
    }

    @Test
    void testActiveRecordingMetadataUpdatedNotificationAfterCommit() throws Exception {
        Target target = createTestTarget();
        Long recordingId = recordingService.createRecording(target.id, "test-metadata-recording");

        eventCapture.reset();

        Metadata newMetadata = Metadata.empty();
        newMetadata.labels().put("test-key", "test-value");
        recordingService.updateMetadata(recordingId, newMetadata);

        ActiveRecordingEvents.ActiveRecordingMetadataUpdated event =
                eventCapture.awaitMetadataUpdatedEvent(recordingId, Duration.ofSeconds(5));

        assertThat(event, notNullValue());
        assertThat(event.getRecordingId(), equalTo(recordingId));
        assertThat(
                event.getCategory(),
                equalTo(ActiveRecordings.RecordingEventCategory.METADATA_UPDATED));

        ActiveRecording recording =
                QuarkusTransaction.requiringNew().call(() -> ActiveRecording.findById(recordingId));
        assertThat(recording, notNullValue());
        assertThat(recording.metadata.labels().get("test-key"), equalTo("test-value"));
    }

    @Test
    void testNoNotificationOnRollback() throws Exception {
        Target target = createTestTarget();

        eventCapture.reset();

        try {
            recordingService.createRecordingAndRollback(target.id, "rollback-recording");
        } catch (RuntimeException expected) {
        }

        Thread.sleep(2000);

        assertThat(eventCapture.hasEvents(), equalTo(false));

        long count =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        Long.valueOf(
                                                ActiveRecording.count(
                                                        "name = ?1", "rollback-recording")));
        assertThat(count, equalTo(0L));
    }

    private Target createTestTarget() {
        return QuarkusTransaction.requiringNew()
                .call(
                        () -> {
                            io.cryostat.discovery.DiscoveryNode node =
                                    new io.cryostat.discovery.DiscoveryNode();
                            node.name = "test-node-" + System.currentTimeMillis();
                            node.nodeType =
                                    io.cryostat.discovery.NodeType.BaseNodeType.JVM.getKind();
                            node.labels = new java.util.HashMap<>();
                            node.persist();

                            Target target = new Target();
                            target.connectUrl =
                                    java.net.URI.create(
                                            "service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi");
                            target.alias = "test-target-" + System.currentTimeMillis();
                            target.discoveryNode = node;
                            target.persist();
                            return target;
                        });
    }

    @ApplicationScoped
    static class TestRecordingService {

        @Inject Event<ActiveRecordingEvents.ActiveRecordingMetadataUpdated> metadataUpdatedEvent;

        @Transactional
        public Long createRecording(Long targetId, String name) {
            Target target = Target.findById(targetId);
            ActiveRecording recording = new ActiveRecording();
            recording.target = target;
            recording.name = name;
            recording.remoteId = System.currentTimeMillis();
            recording.state = RecordingState.RUNNING;
            recording.duration = 0;
            recording.startTime = System.currentTimeMillis();
            recording.archiveOnStop = false;
            recording.continuous = true;
            recording.toDisk = true;
            recording.maxSize = 0;
            recording.maxAge = 0;
            recording.external = false;
            recording.metadata = new Metadata();
            recording.persist();
            return recording.id;
        }

        @Transactional
        public void stopRecording(Long recordingId) {
            ActiveRecording recording = ActiveRecording.findById(recordingId);
            recording.state = RecordingState.STOPPED;
            recording.persist();
        }

        @Transactional
        public void deleteRecording(Long recordingId) {
            ActiveRecording recording = ActiveRecording.findById(recordingId);
            recording.delete();
        }

        @Transactional
        public void updateMetadata(Long recordingId, Metadata metadata) {
            ActiveRecording recording = ActiveRecording.findById(recordingId);
            recording.setMetadata(metadata);
            recording.persist();
            metadataUpdatedEvent.fire(
                    new ActiveRecordingEvents.ActiveRecordingMetadataUpdated(
                            recording.id.longValue()));
        }

        @Transactional
        public void createRecordingAndRollback(Long targetId, String name) {
            Target target = Target.findById(targetId);
            ActiveRecording recording = new ActiveRecording();
            recording.target = target;
            recording.name = name;
            recording.remoteId = System.currentTimeMillis();
            recording.state = RecordingState.RUNNING;
            recording.duration = 0;
            recording.startTime = System.currentTimeMillis();
            recording.archiveOnStop = false;
            recording.continuous = true;
            recording.toDisk = true;
            recording.maxSize = 0;
            recording.maxAge = 0;
            recording.external = false;
            recording.metadata = new Metadata();
            recording.persist();
            throw new RuntimeException("force rollback");
        }
    }

    @ApplicationScoped
    static class TestEventCapture {

        private volatile CompletableFuture<ActiveRecordingEvents.ActiveRecordingCreated>
                createdFuture = new CompletableFuture<>();
        private volatile CompletableFuture<ActiveRecordingEvents.ActiveRecordingStopped>
                stoppedFuture = new CompletableFuture<>();
        private volatile CompletableFuture<ActiveRecordingEvents.ActiveRecordingDeleted>
                deletedFuture = new CompletableFuture<>();
        private volatile CompletableFuture<ActiveRecordingEvents.ActiveRecordingMetadataUpdated>
                metadataUpdatedFuture = new CompletableFuture<>();

        void onCreated(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        ActiveRecordingEvents.ActiveRecordingCreated event) {
            createdFuture.complete(event);
        }

        void onStopped(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        ActiveRecordingEvents.ActiveRecordingStopped event) {
            stoppedFuture.complete(event);
        }

        void onDeleted(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        ActiveRecordingEvents.ActiveRecordingDeleted event) {
            deletedFuture.complete(event);
        }

        void onMetadataUpdated(
                @Observes(during = TransactionPhase.AFTER_SUCCESS)
                        ActiveRecordingEvents.ActiveRecordingMetadataUpdated event) {
            metadataUpdatedFuture.complete(event);
        }

        void reset() {
            createdFuture = new CompletableFuture<>();
            stoppedFuture = new CompletableFuture<>();
            deletedFuture = new CompletableFuture<>();
            metadataUpdatedFuture = new CompletableFuture<>();
        }

        boolean hasEvents() {
            return createdFuture.isDone()
                    || stoppedFuture.isDone()
                    || deletedFuture.isDone()
                    || metadataUpdatedFuture.isDone();
        }

        ActiveRecordingEvents.ActiveRecordingCreated awaitCreatedEvent(
                Long recordingId, Duration timeout) throws Exception {
            ActiveRecordingEvents.ActiveRecordingCreated event =
                    createdFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!recordingId.equals(event.getRecordingId())) {
                throw new AssertionError(
                        String.format(
                                "Expected recording ID %d but got %d",
                                recordingId, event.getRecordingId()));
            }
            return event;
        }

        ActiveRecordingEvents.ActiveRecordingStopped awaitStoppedEvent(
                Long recordingId, Duration timeout) throws Exception {
            ActiveRecordingEvents.ActiveRecordingStopped event =
                    stoppedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!recordingId.equals(event.getRecordingId())) {
                throw new AssertionError(
                        String.format(
                                "Expected recording ID %d but got %d",
                                recordingId, event.getRecordingId()));
            }
            return event;
        }

        ActiveRecordingEvents.ActiveRecordingDeleted awaitDeletedEvent(
                Long recordingId, Duration timeout) throws Exception {
            ActiveRecordingEvents.ActiveRecordingDeleted event =
                    deletedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!recordingId.equals(event.getRecordingId())) {
                throw new AssertionError(
                        String.format(
                                "Expected recording ID %d but got %d",
                                recordingId, event.getRecordingId()));
            }
            return event;
        }

        ActiveRecordingEvents.ActiveRecordingMetadataUpdated awaitMetadataUpdatedEvent(
                Long recordingId, Duration timeout) throws Exception {
            ActiveRecordingEvents.ActiveRecordingMetadataUpdated event =
                    metadataUpdatedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!recordingId.equals(event.getRecordingId())) {
                throw new AssertionError(
                        String.format(
                                "Expected recording ID %d but got %d",
                                recordingId, event.getRecordingId()));
            }
            return event;
        }
    }
}
