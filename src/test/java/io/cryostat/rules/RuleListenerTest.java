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
package io.cryostat.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import io.cryostat.expressions.MatchExpression;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.rules.Rule.RuleEventCategory;

import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A Rule can be edited in place. Edits to the settings that shape the rule's recording, or that
 * change which Targets the rule applies to, require the rule to be re-activated. Edits that only
 * concern the archival schedule or the description do not, and re-activating for them would stop
 * and re-create a healthy recording for nothing. The listener that publishes the Rule UPDATED event
 * is where the previously loaded state and the updated state meet, so it is what classifies the
 * edit.
 */
public class RuleListenerTest {

    EventBus bus;
    Rule.Listener listener;
    Rule rule;

    @BeforeEach
    void setUp() {
        bus = mock(EventBus.class);
        listener = new Rule.Listener();
        listener.bus = bus;
        listener.createdEvent = mockEvent();
        listener.updatedEvent = mockEvent();
        listener.deletedEvent = mockEvent();

        rule = new Rule();
        rule.id = 1L;
        rule.name = "test_rule";
        rule.description = "";
        rule.matchExpression = new MatchExpression("true");
        rule.matchExpression.id = 2L;
        rule.eventSpecifier = "template=Continuous,type=TARGET";
        rule.archivalPeriodSeconds = 60;
        rule.initialDelaySeconds = 0;
        rule.preservedArchives = 3;
        rule.maxAgeSeconds = 0;
        rule.maxSizeBytes = 0;
        rule.metadata = new Metadata(new HashMap<>(Map.of("a", "b")));
        rule.enabled = true;
    }

    @Test
    void archivalScheduleEditDoesNotAffectRecording() {
        listener.postLoad(rule);

        rule.archivalPeriodSeconds = 120;
        rule.initialDelaySeconds = 10;
        rule.preservedArchives = 5;

        assertFalse(update().recordingAffected());
    }

    @Test
    void descriptionEditDoesNotAffectRecording() {
        listener.postLoad(rule);

        rule.description = "a new description";

        assertFalse(update().recordingAffected());
    }

    @Test
    void eventSpecifierEditAffectsRecording() {
        listener.postLoad(rule);

        rule.eventSpecifier = "template=Profiling,type=TARGET";

        assertTrue(update().recordingAffected());
    }

    @Test
    void maxAgeEditAffectsRecording() {
        listener.postLoad(rule);

        rule.maxAgeSeconds = 30;

        assertTrue(update().recordingAffected());
    }

    @Test
    void maxSizeEditAffectsRecording() {
        listener.postLoad(rule);

        rule.maxSizeBytes = 1024;

        assertTrue(update().recordingAffected());
    }

    @Test
    void metadataEditAffectsRecording() {
        listener.postLoad(rule);

        rule.metadata = new Metadata(new HashMap<>(Map.of("a", "c")));

        assertTrue(update().recordingAffected());
    }

    @Test
    void matchExpressionEditAffectsRecording() {
        listener.postLoad(rule);

        rule.matchExpression = new MatchExpression("false");
        rule.matchExpression.id = 3L;

        assertTrue(update().recordingAffected());
    }

    @Test
    void enablingAffectsRecording() {
        rule.enabled = false;
        listener.postLoad(rule);

        rule.enabled = true;

        assertTrue(update().recordingAffected());
    }

    /**
     * A rule that did not archive has no archival job to refresh, and only activation creates one,
     * so the edit that turns archiving on must still re-activate the rule.
     */
    @Test
    void startingToArchiveAffectsRecording() {
        rule.preservedArchives = 0;
        listener.postLoad(rule);

        rule.preservedArchives = 3;

        assertTrue(update().recordingAffected());
    }

    @Test
    void editingArchivalSettingsOfNonArchiverDoesNotAffectRecording() {
        rule.preservedArchives = 0;
        listener.postLoad(rule);

        rule.archivalPeriodSeconds = 0;
        rule.initialDelaySeconds = 5;

        assertFalse(update().recordingAffected());
    }

    /**
     * Once an update has been published the new state is the baseline, so that a second edit in the
     * same persistence context is compared with the first one rather than with the state that was
     * originally loaded.
     */
    @Test
    void consecutiveUpdatesAreComparedWithTheLatestPublishedState() {
        listener.postLoad(rule);

        rule.maxAgeSeconds = 30;
        assertTrue(update().recordingAffected());

        rule.preservedArchives = 9;
        assertFalse(update().recordingAffected());
    }

    /**
     * Nothing is known about a rule that was not loaded through the listener, so the safe answer is
     * that its recording is affected.
     */
    @Test
    void unknownPriorStateIsTreatedAsAffectingRecording() {
        rule.preservedArchives = 5;

        assertTrue(update().recordingAffected());
    }

    @Test
    void newlyPersistedRuleIsTheBaselineForLaterUpdates() {
        listener.postPersist(rule);

        rule.archivalPeriodSeconds = 120;

        assertFalse(update().recordingAffected());
    }

    @Test
    void createdAndDeletedEventsAreActionable() {
        listener.postPersist(rule);
        listener.postRemove(rule);

        ArgumentCaptor<RuleEvent> captor = ArgumentCaptor.forClass(RuleEvent.class);
        verify(bus, times(2)).publish(eq(Rule.RULE_ADDRESS), captor.capture());
        assertEquals(RuleEventCategory.CREATED, captor.getAllValues().get(0).category());
        assertTrue(captor.getAllValues().get(0).recordingAffected());
        assertEquals(RuleEventCategory.DELETED, captor.getAllValues().get(1).category());
        assertTrue(captor.getAllValues().get(1).recordingAffected());
    }

    private RuleEvent update() {
        listener.postUpdate(rule);
        ArgumentCaptor<RuleEvent> captor = ArgumentCaptor.forClass(RuleEvent.class);
        verify(bus, atLeastOnce()).publish(eq(Rule.RULE_ADDRESS), captor.capture());
        RuleEvent last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(RuleEventCategory.UPDATED, last.category());
        return last;
    }

    @SuppressWarnings("unchecked")
    private static <T> Event<T> mockEvent() {
        return mock(Event.class);
    }
}
