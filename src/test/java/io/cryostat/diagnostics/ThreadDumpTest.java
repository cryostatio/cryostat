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

import io.cryostat.audit.AuditTestBase;
import io.cryostat.diagnostic.Diagnostics;
import io.cryostat.diagnostic.ThreadDump;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ThreadDumpTest.class)
@TestHTTPEndpoint(Diagnostics.class)
public class ThreadDumpTest extends AuditTestBase {

    @Inject EntityManager em;

    @Test
    public void testThreadDumpRequestCreatesEntity() {
        int targetId = defineSelfCustomTarget();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        Assertions.assertNotNull(jobId, "Job ID should be returned");

        ThreadDump dump = ThreadDump.<ThreadDump>find("jobId", jobId).firstResult();
        Assertions.assertNotNull(dump, "ThreadDump entity should be created");
        Assertions.assertEquals(ThreadDump.Status.REQUESTED, dump.status);
        Assertions.assertEquals(Long.valueOf(targetId), dump.target.id);
        Assertions.assertNotNull(dump.requestedAt);
        Assertions.assertNull(dump.completedAt);
        Assertions.assertNull(dump.filename);
    }

    @Test
    public void testThreadDumpEntityHasCorrectFormat() {
        int targetId = defineSelfCustomTarget();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .queryParam("format", "threadPrint")
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        ThreadDump dump = ThreadDump.<ThreadDump>find("jobId", jobId).firstResult();
        Assertions.assertNotNull(dump);
        Assertions.assertEquals("threadPrint", dump.format);
    }

    @Test
    public void testThreadDumpEntityCreatesAuditLog() {
        int targetId = defineSelfCustomTarget();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        AuditReader auditReader = AuditReaderFactory.get(em);
        ThreadDump dump = ThreadDump.<ThreadDump>find("jobId", jobId).firstResult();
        Assertions.assertNotNull(dump, "ThreadDump entity should exist");

        var revisions = auditReader.getRevisions(ThreadDump.class, dump.id);
        Assertions.assertFalse(revisions.isEmpty(), "Should have at least one audit revision");
    }

    @Test
    public void testThreadDumpEntityHasCorrectTimestamp() {
        int targetId = defineSelfCustomTarget();
        long beforeRequest = System.currentTimeMillis();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        long afterRequest = System.currentTimeMillis();

        ThreadDump dump = ThreadDump.<ThreadDump>find("jobId", jobId).firstResult();
        Assertions.assertNotNull(dump);
        Assertions.assertTrue(
                dump.requestedAt >= beforeRequest,
                "Requested timestamp should be after or equal to before request time");
        Assertions.assertTrue(
                dump.requestedAt <= afterRequest,
                "Requested timestamp should be before or equal to after request time");
    }

    @Test
    public void testThreadDumpEntityHasCorrectTargetReference() {
        int targetId = defineSelfCustomTarget();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        ThreadDump dump = ThreadDump.<ThreadDump>find("jobId", jobId).firstResult();
        Assertions.assertNotNull(dump);
        Assertions.assertNotNull(dump.target);
        Assertions.assertEquals(Long.valueOf(targetId), dump.target.id);
    }

    @Test
    public void testThreadDumpRequestOnInvalidTargetFails() {
        given().log()
                .all()
                .when()
                .pathParam("targetId", Integer.MAX_VALUE)
                .post("targets/{targetId}/threaddump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);

        long count = ThreadDump.count();
        Assertions.assertEquals(0, count, "No ThreadDump entity should be created");
    }

    @Test
    public void testThreadDumpAuditQueryIntegration() {
        int targetId = defineSelfCustomTarget();
        long startTime = System.currentTimeMillis();

        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        long endTime = System.currentTimeMillis();

        Integer revisionNumber =
                given().basePath("/")
                        .log()
                        .all()
                        .queryParam("startTime", startTime)
                        .queryParam("endTime", endTime)
                        .queryParam("pageSize", 10)
                        .when()
                        .get("/api/beta/audit/revisions")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .body("revisions", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Should have at least one revision");

        given().basePath("/")
                .log()
                .all()
                .when()
                .get("/api/beta/audit/revisions/{rev}", revisionNumber)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .body("rev", org.hamcrest.Matchers.equalTo(revisionNumber))
                .body("entities", org.hamcrest.Matchers.notNullValue())
                .body("entities.ThreadDump", org.hamcrest.Matchers.notNullValue())
                .body("entities.ThreadDump", org.hamcrest.Matchers.instanceOf(java.util.List.class))
                .body("entities.ThreadDump[0].jobId", org.hamcrest.Matchers.equalTo(jobId))
                .body("entities.ThreadDump[0].status", org.hamcrest.Matchers.equalTo("REQUESTED"))
                .body("entities.ThreadDump[0].revtype", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    public void testMultipleThreadDumpRequestsCreateMultipleEntities() {
        int targetId = defineSelfCustomTarget();

        for (int i = 0; i < 3; i++) {
            given().log()
                    .all()
                    .when()
                    .pathParam("targetId", targetId)
                    .post("targets/{targetId}/threaddump")
                    .then()
                    .log()
                    .all()
                    .assertThat()
                    .statusCode(200);
        }

        long count = ThreadDump.count("target.id = ?1", Long.valueOf(targetId));
        Assertions.assertEquals(3, count, "Expected three ThreadDump entities to be created");
    }
}
