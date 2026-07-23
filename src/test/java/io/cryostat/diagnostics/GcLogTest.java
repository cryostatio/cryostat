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
package io.cryostat.diagnostics;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.stream.Stream;

import io.cryostat.audit.AuditTestBase;
import io.cryostat.diagnostic.GcLog;
import io.cryostat.diagnostic.GcLogs;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusTest
@TestProfile(GcLogTest.class)
@TestHTTPEndpoint(GcLogs.class)
public class GcLogTest extends AuditTestBase {

    @Inject EntityManager em;

    // ── Non-agent target: all session management endpoints must return 400 ────────

    @Test
    public void testEnableGcLoggingOnJmxTargetReturns400() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testPatchGcLoggingOnJmxTargetReturns400() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .request("PATCH", "targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testDisableGcLoggingOnJmxTargetReturns400() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .delete("targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testPullGcLogOnJmxTargetReturns400() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    // ── Invalid query parameter characters ───────────────────────────────────────

    static Stream<Arguments> invalidParams() {
        return Stream.of(
                Arguments.of("gc heap", "time,level"),
                Arguments.of("gc\theap", "time,level"),
                Arguments.of("gc\nheap", "time,level"),
                Arguments.of("gc%20heap", "time,level"),
                Arguments.of("gc&other", "time,level"),
                Arguments.of("gc#frag", "time,level"),
                Arguments.of("gc", "time level"),
                Arguments.of("gc", "time%2Clevel"),
                Arguments.of("gc", "time\tlevel"),
                Arguments.of("gc", "time\nlevel"),
                Arguments.of("gc", "time&level"),
                Arguments.of("gc", "time#frag"),
                Arguments.of("<script>", "time,level"),
                Arguments.of("gc", "<script>"));
    }

    @ParameterizedTest
    @MethodSource("invalidParams")
    public void testEnableGcLoggingWithInvalidParamsReturns400(String what, String decorators) {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .queryParam("what", what)
                .queryParam("decorators", decorators)
                .post("targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @ParameterizedTest
    @MethodSource("invalidParams")
    public void testReconfigureGcLoggingWithInvalidParamsReturns400(
            String what, String decorators) {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .queryParam("what", what)
                .queryParam("decorators", decorators)
                .request("PATCH", "targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    // ── Invalid target ID ─────────────────────────────────────────────────────────

    @Test
    public void testEnableGcLoggingOnInvalidTargetReturns404() {
        given().log()
                .all()
                .when()
                .pathParam("targetId", Integer.MAX_VALUE)
                .post("targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    // ── GcLog entity lifecycle — Envers-based assertions ─────────────────────────

    @Test
    @Transactional
    public void testGcLogEntityEnableCreatesActiveRow() {
        int targetId = defineSelfCustomTarget();
        Target target = Target.getTargetById(targetId);

        long before = System.currentTimeMillis();
        GcLog session = GcLog.enable(target, "gc", "time,level");
        session.persist();
        long after = System.currentTimeMillis();

        Assertions.assertNotNull(session.id);
        Assertions.assertEquals(GcLog.Status.ACTIVE, session.status);
        Assertions.assertEquals("gc", session.what);
        Assertions.assertEquals("time,level", session.decorators);
        Assertions.assertNull(session.filename);
        Assertions.assertTrue(session.enabledAt >= before);
        Assertions.assertTrue(session.enabledAt <= after);
        Assertions.assertNull(session.lastModifiedAt);
    }

    @Test
    public void testGcLogSessionLifecycleCreatesAuditRevisions() {
        int targetId = defineSelfCustomTarget();

        // Persist a GcLog session and immediately delete it (simulating enable + disable).
        long sessionId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    Target target = Target.getTargetById(targetId);
                                    GcLog session = GcLog.enable(target, "gc", "time,level");
                                    session.persist();
                                    return session.id;
                                });

        // Touch the row once (simulating markPulled) so we get an UPDATE revision.
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            GcLog session = GcLog.findById(sessionId);
                            session.markPulled("somefile.gclog", 1024L);
                            session.persist();
                        });

        // Delete in a separate transaction — Envers must record the DELETE revision.
        QuarkusTransaction.requiringNew().run(() -> GcLog.deleteById(sessionId));

        // Primary table must be empty.
        long primaryCount = GcLog.count();
        Assertions.assertEquals(
                0, primaryCount, "GcLog primary table should be empty after delete");

        // _AUD must have at least 3 revisions: INSERT + UPDATE + DELETE.
        AuditReader auditReader = AuditReaderFactory.get(em);
        List<?> revisions =
                auditReader
                        .createQuery()
                        .forRevisionsOfEntity(GcLog.class, false, true)
                        .getResultList();
        Assertions.assertTrue(
                revisions.size() >= 3,
                "GcLog_AUD should have at least 3 revisions (INSERT + UPDATE + DELETE)");
    }

    @Test
    @Transactional
    public void testGcLogSessionRowUnchangedAfterNoPull() {
        int targetId = defineSelfCustomTarget();
        Target target = Target.getTargetById(targetId);

        GcLog session = GcLog.enable(target, "gc", "time,level");
        session.persist();

        // Simulate the no-content path: markPulled is never called.
        // The session row must remain in its initial ACTIVE state.
        Assertions.assertNull(session.filename);
        Assertions.assertNull(session.size);
        Assertions.assertNull(session.lastModifiedAt);
        Assertions.assertEquals(GcLog.Status.ACTIVE, session.status);
    }

    @Test
    public void testListGcLogsForInvalidTargetReturns404() {
        given().log()
                .all()
                .when()
                .pathParam("targetId", Integer.MAX_VALUE)
                .get("targets/{targetId}/gclogs")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testDownloadInvalidGcLogKeyReturns404() {
        given().log()
                .all()
                .when()
                .get("/api/beta/diagnostics/gclog/download/abcd1234")
                .then()
                .assertThat()
                .statusCode(404);
    }
}
