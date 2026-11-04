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

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class RulesArchiverTest extends AbstractTransactionalTestBase {

    static String RULE_NAME = "periodic_archiver";

    static final String rule = ruleJson(10);

    static String ruleJson(int archivalPeriodSeconds) {
        return ruleJson(archivalPeriodSeconds, 3);
    }

    static String ruleJson(int archivalPeriodSeconds, int preservedArchives) {
        return String.format(
                """
                {
                  "name": "%s",
                  "description": "",
                  "matchExpression": "true",
                  "eventSpecifier": "template=Continuous,type=TARGET",
                  "archivalPeriodSeconds": %d,
                  "initialDelaySeconds": 0,
                  "preservedArchives": %d,
                  "maxAgeSeconds": 0,
                  "maxSizeBytes": 10485760,
                  "enabled": true
                }
                """,
                RULE_NAME, archivalPeriodSeconds, preservedArchives);
    }

    @AfterEach
    void cleanupRulesArchiverTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void test()
            throws TimeoutException, InterruptedException, IOException, DeploymentException {
        int id = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().body(rule)
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);

        // Wait for rule creation to be processed
        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));

        // Wait for the rule to activate and start the recording
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(15));

        // Wait for archives to be created. With preservedArchives=3 and archivalPeriodSeconds=10:
        // Note: initialDelaySeconds=0 is converted to archivalPeriodSeconds (10s) in RuleExecutor
        // - 1st archive at ~10s after recording starts (initial delay)
        // - 2nd archive at ~20s after recording starts
        // - 3rd archive at ~30s after recording starts
        // - 4th archive at ~40s after recording starts (triggers deletion of 1st, then creates 4th)

        // Wait for first 3 archives to be created
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));

        // Wait for deletion that occurs with 4th archive job
        webSocketClient.expectNotification("ArchivedRecordingDeleted", Duration.ofSeconds(15));

        // Wait for the 4th archive to be created (happens after deletion in same job)
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(5));

        // Stop further background jobs before checking results
        given().log()
                .all()
                // do not clean, or else Cryostat will archive the recording on stop and
                // create an additional copy
                .queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204)
                .body(Matchers.emptyOrNullString());

        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3));
    }

    /**
     * Regression test for GitHub issue #1885. Deleting the rule's active recording and then having
     * the rule re-activate on the target creates a replacement recording with a new remoteId. The
     * periodic archival must keep running against the replacement recording instead of dying
     * permanently because its Quartz job still refers to the old remoteId. Archives produced before
     * the re-activation must be left in place, and must count towards preservedArchives so that the
     * oldest of them is the first to be pruned once the limit is exceeded.
     */
    @Test
    public void testArchivingContinuesAfterRuleRecordingIsRecreated()
            throws TimeoutException, InterruptedException, IOException, DeploymentException {
        int id = defineSelfCustomTarget();
        String recordingName = String.format("auto_%s", RULE_NAME);

        given().body(ruleJson(3))
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);

        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));

        // Let the first archive be produced so that the archival job is demonstrably running
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(10));

        int originalRemoteId = ruleRecordingRemoteId(id, recordingName);
        List<String> initialArchives = archivedRecordingNames();
        Assertions.assertEquals(
                1, initialArchives.size(), "exactly one archive should exist before re-activation");
        String initialArchive = initialArchives.get(0);

        // Delete the rule's recording out from under the running archival job
        given().log()
                .all()
                .pathParams(Map.of("targetId", id, "remoteId", originalRemoteId))
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
        webSocketClient.expectNotification("ActiveRecordingDeleted", Duration.ofSeconds(5));

        // Have the rule re-activate on the target. A label edit changes the recording the rule
        // starts, unlike a description or archival schedule edit, so it re-activates the rule.
        given().log()
                .all()
                .body("{\"metadata\": {\"labels\": {\"re-trigger\": \"activation\"}}}")
                .contentType(ContentType.JSON)
                .pathParam("ruleName", RULE_NAME)
                .patch("/api/v4/rules/{ruleName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));

        int recreatedRemoteId = ruleRecordingRemoteId(id, recordingName);
        Assertions.assertNotEquals(
                originalRemoteId,
                recreatedRemoteId,
                "the rule recording should have been re-created with a new remoteId");

        // Deleting the active recording and re-activating the rule must not touch archives that
        // were already produced
        assertThat(
                "archive from the initial activation must survive re-activation",
                archivedRecordingNames(),
                hasItem(initialArchive));

        // The archival schedule must survive the recording being re-created. The Quartz job was
        // created for the original remoteId, so it must have been pointed at the replacement
        // recording rather than failing and unscheduling itself. These are the 2nd and 3rd
        // archives overall, so together with the initial archive the retained set is now full.
        // Identities are taken from the notifications rather than from a storage snapshot, since
        // the next archival job may already be running by the time a snapshot would be taken.
        String secondArchive =
                archivedName(
                        webSocketClient.expectNotification(
                                "ArchivedRecordingCreated", Duration.ofSeconds(10)));
        String thirdArchive =
                archivedName(
                        webSocketClient.expectNotification(
                                "ArchivedRecordingCreated", Duration.ofSeconds(10)));
        assertThat(
                "archives after re-activation must be new files",
                List.of(secondArchive, thirdArchive),
                not(hasItem(initialArchive)));

        // The 4th archive overall exceeds preservedArchives=3. Within a single job the oldest
        // archive is deleted before the new one is created, so the first deletion must be the
        // archive from the initial activation. That only holds if archives from before the
        // re-activation were correctly accounted for in the rollover.
        JsonObject deleted =
                webSocketClient.expectNotification(
                        "ArchivedRecordingDeleted", Duration.ofSeconds(10));
        Assertions.assertEquals(
                initialArchive,
                archivedName(deleted),
                "the oldest archive, from the initial activation, should be pruned first");
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(10));

        // Between a job's delete and its create the archive count briefly dips to 2, and an
        // archive-on-stop copy or a slow notification could briefly show a 4th. These transient
        // states are acceptable, so poll for the settled state instead of sampling it once.
        awaitArchives(
                "archives should settle at preservedArchives=3 with the initial archive pruned",
                names -> names.size() == 3 && !names.contains(initialArchive),
                Duration.ofSeconds(10));

        given().log()
                .all()
                .queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));
    }

    /**
     * Reproduces a manual test of GitHub issue #1885. The rule's recording is deleted and nothing
     * else touches the rule or the target. Nothing re-activates a rule when its recording goes
     * missing, so the archival job itself must notice, have the rule re-activated so that the
     * recording is re-created, and carry on archiving. Without that, the job unschedules itself and
     * the rule silently stops archiving for good.
     */
    @Test
    public void testArchivingResumesAfterRuleRecordingIsDeletedWithoutReactivation()
            throws Exception {
        int id = defineSelfCustomTarget();
        String recordingName = String.format("auto_%s", RULE_NAME);
        JobKey jobKey = new JobKey(RULE_NAME, "rule.scheduled-archive." + this.selfJvmId);
        TriggerKey triggerKey = new TriggerKey(RULE_NAME, jobKey.getGroup());

        given().body(ruleJson(3))
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);
        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(10));

        int originalRemoteId = ruleRecordingRemoteId(id, recordingName);

        given().pathParams(Map.of("targetId", id, "remoteId", originalRemoteId))
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("ActiveRecordingDeleted", Duration.ofSeconds(5));

        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(20));
        int recreatedRemoteId = ruleRecordingRemoteId(id, recordingName);
        Assertions.assertNotEquals(
                originalRemoteId,
                recreatedRemoteId,
                "the rule recording should have been re-created without any other trigger");

        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(20));
        awaitCondition(
                "archival trigger should be alive again",
                () -> scheduler.getTriggerState(triggerKey) == Trigger.TriggerState.NORMAL,
                Duration.ofSeconds(10));
        Assertions.assertEquals(
                recreatedRemoteId,
                scheduler.getJobDetail(jobKey).getJobDataMap().getLong("recording"),
                "archival job must be bound to the re-created recording");

        given().queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));
    }

    /**
     * A Rule can be edited in place through the API without being toggled and without its Target
     * being rediscovered. The archival job created under the old settings must pick up the edited
     * preservedArchives and archivalPeriodSeconds from the resulting Rule UPDATED event alone.
     * Those settings only concern the archival schedule, so the rule's recording must be left
     * running rather than being stopped and re-created.
     */
    @Test
    public void testRuleEditReachesExistingArchivalJob() throws Exception {
        int id = defineSelfCustomTarget();
        String recordingName = String.format("auto_%s", RULE_NAME);
        JobKey jobKey = new JobKey(RULE_NAME, "rule.scheduled-archive." + this.selfJvmId);
        TriggerKey triggerKey = new TriggerKey(RULE_NAME, jobKey.getGroup());

        // a long period so that no archival fires during the test
        given().body(ruleJson(3600))
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);
        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));

        awaitCondition(
                "archival job should be scheduled for the rule",
                () -> scheduler.checkExists(jobKey),
                Duration.ofSeconds(10));
        int originalRemoteId = ruleRecordingRemoteId(id, recordingName);
        Assertions.assertEquals(
                3, scheduler.getJobDetail(jobKey).getJobDataMap().getInt("preservedArchives"));
        Assertions.assertEquals(
                3_600_000L, ((SimpleTrigger) scheduler.getTrigger(triggerKey)).getRepeatInterval());

        given().body("{\"preservedArchives\": 5, \"archivalPeriodSeconds\": 7200}")
                .contentType(ContentType.JSON)
                .pathParam("ruleName", RULE_NAME)
                .patch("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(200);
        webSocketClient.expectNotification("RuleUpdated", Duration.ofSeconds(5));

        awaitCondition(
                "existing job should carry the edited preservedArchives",
                () ->
                        scheduler.getJobDetail(jobKey).getJobDataMap().getInt("preservedArchives")
                                == 5,
                Duration.ofSeconds(10));
        awaitCondition(
                "existing trigger should use the edited archival period",
                () ->
                        ((SimpleTrigger) scheduler.getTrigger(triggerKey)).getRepeatInterval()
                                == 7_200_000L,
                Duration.ofSeconds(10));

        Assertions.assertThrows(
                TimeoutException.class,
                () ->
                        webSocketClient.expectNotification(
                                "ActiveRecordingCreated", Duration.ofSeconds(3)),
                "a schedule-only edit must not re-create the rule recording");
        Assertions.assertEquals(
                originalRemoteId,
                ruleRecordingRemoteId(id, recordingName),
                "a schedule-only edit must leave the rule recording in place");

        given().queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));
    }

    /**
     * Editing a rule so that its recording must be replaced stops the old recording. What it
     * captured must be archived before it is replaced rather than lost, so one extra archive
     * appears, it counts towards preservedArchives, and the archive is of the recording that was
     * replaced, not of its successor.
     */
    @Test
    public void testReplacedRuleRecordingIsArchivedBeforeItIsReplaced() throws Exception {
        int id = defineSelfCustomTarget();
        String recordingName = String.format("auto_%s", RULE_NAME);

        given().body(ruleJson(3600))
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);
        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));
        int originalRemoteId = ruleRecordingRemoteId(id, recordingName);
        Assertions.assertTrue(
                archivedRecordingNames().isEmpty(),
                "a long archival period means nothing is archived before the edit");

        given().body("{\"maxSizeBytes\": 5242880}")
                .contentType(ContentType.JSON)
                .pathParam("ruleName", RULE_NAME)
                .patch("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(200);
        webSocketClient.expectNotification("RuleUpdated", Duration.ofSeconds(5));

        JsonObject archived =
                webSocketClient.expectNotification(
                        "ArchivedRecordingCreated", Duration.ofSeconds(15));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(15));
        Assertions.assertNotEquals(
                originalRemoteId,
                ruleRecordingRemoteId(id, recordingName),
                "a recording-affecting edit must replace the rule recording");

        List<String> archives = archivedRecordingNames();
        Assertions.assertEquals(
                1, archives.size(), "the replaced recording must have been archived exactly once");
        Assertions.assertEquals(archivedName(archived), archives.get(0));

        Assertions.assertThrows(
                TimeoutException.class,
                () ->
                        webSocketClient.expectNotification(
                                "ArchivedRecordingCreated", Duration.ofSeconds(5)),
                "replacing the recording must not archive it a second time");

        given().queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));
    }

    /**
     * When the rule's recording has been deleted and the archival job fires, it re-creates the
     * recording but archives nothing on that tick. Pruning exists to make room for an archive that
     * is about to be added, so with no archive incoming the archives the rule has already collected
     * must all be left in place, even when they already fill preservedArchives.
     */
    @Test
    public void testNoArchiveIsPrunedWhenRecordingMissingOnArchivalTick() throws Exception {
        int id = defineSelfCustomTarget();
        String recordingName = String.format("auto_%s", RULE_NAME);

        given().body(ruleJson(10, 2))
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);
        webSocketClient.expectNotification("RuleCreated", Duration.ofSeconds(5));
        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(10));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));
        int originalRemoteId = ruleRecordingRemoteId(id, recordingName);
        List<String> archivesBefore = archivedRecordingNames();
        Assertions.assertEquals(2, archivesBefore.size());

        given().pathParams(Map.of("targetId", id, "remoteId", originalRemoteId))
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("ActiveRecordingDeleted", Duration.ofSeconds(5));

        webSocketClient.expectNotification("ActiveRecordingCreated", Duration.ofSeconds(20));
        Assertions.assertNotEquals(
                originalRemoteId,
                ruleRecordingRemoteId(id, recordingName),
                "the missing rule recording should have been re-created");

        Assertions.assertThrows(
                TimeoutException.class,
                () ->
                        webSocketClient.expectNotification(
                                "ArchivedRecordingDeleted", Duration.ofSeconds(3)),
                "no archive may be pruned when no new archive is being added");
        Assertions.assertEquals(
                archivesBefore.stream().sorted().toList(),
                archivedRecordingNames().stream().sorted().toList(),
                "the archives already collected must be left untouched");

        given().queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .statusCode(204);
        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));
    }

    private interface Check {
        boolean test() throws Exception;
    }

    private void awaitCondition(String description, Check check, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.test()) {
                return;
            }
            Thread.sleep(250);
        }
        Assertions.fail(description);
    }

    private static String archivedName(JsonObject notification) {
        return notification.getJsonObject("message").getJsonObject("recording").getString("name");
    }

    private List<String> archivedRecordingNames() {
        return given().when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .assertThat()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("name", String.class);
    }

    private void awaitArchives(
            String description, Predicate<List<String>> condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> last = List.of();
        while (System.nanoTime() < deadline) {
            last = archivedRecordingNames();
            if (condition.test(last)) {
                return;
            }
            Thread.sleep(250);
        }
        Assertions.fail(description + "; last observed archives: " + last);
    }

    private int ruleRecordingRemoteId(int targetId, String recordingName) {
        return given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt(String.format("find { it.name == '%s' }.remoteId", recordingName));
    }
}
