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
import io.cryostat.diagnostic.GarbageCollection;
import io.cryostat.targets.Target;

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

@QuarkusTest
@TestProfile(GarbageCollectionTest.class)
@TestHTTPEndpoint(Diagnostics.class)
public class GarbageCollectionTest extends AuditTestBase {

    @Inject EntityManager em;

    @Test
    public void testGcTriggerCreatesAuditEntity() {
        int targetId = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        long count = GarbageCollection.count("target.id = ?1", Long.valueOf(targetId));
        Assertions.assertEquals(1, count, "Expected one GarbageCollection entity to be created");
    }

    @Test
    public void testGcTriggerCreatesAuditLog() {
        int targetId = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        AuditReader auditReader = AuditReaderFactory.get(em);
        var gcEntity =
                GarbageCollection.<GarbageCollection>find("target.id", Long.valueOf(targetId))
                        .firstResult();
        Assertions.assertNotNull(gcEntity, "GarbageCollection entity should exist");

        var revisions = auditReader.getRevisions(GarbageCollection.class, gcEntity.id);
        Assertions.assertTrue(!revisions.isEmpty(), "Should have at least one audit revision");
        Assertions.assertTrue(
                revisions.size() >= 1, "Should have at least one audit revision for creation");
    }

    @Test
    public void testGcEntityHasCorrectTimestamp() {
        int targetId = defineSelfCustomTarget();
        long beforeTrigger = System.currentTimeMillis();

        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        long afterTrigger = System.currentTimeMillis();

        var gcEntity =
                GarbageCollection.<GarbageCollection>find("target.id", Long.valueOf(targetId))
                        .firstResult();
        Assertions.assertNotNull(gcEntity);
        Assertions.assertTrue(
                gcEntity.triggeredAt >= beforeTrigger,
                "Triggered timestamp should be after or equal to before trigger time");
        Assertions.assertTrue(
                gcEntity.triggeredAt <= afterTrigger,
                "Triggered timestamp should be before or equal to after trigger time");
    }

    @Test
    public void testGcEntityHasCorrectTargetReference() {
        int targetId = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        var gcEntity =
                GarbageCollection.<GarbageCollection>find("target.id", Long.valueOf(targetId))
                        .firstResult();
        Assertions.assertNotNull(gcEntity);
        Assertions.assertNotNull(gcEntity.target);
        Assertions.assertEquals(Long.valueOf(targetId), gcEntity.target.id);
    }

    @Test
    public void testMultipleGcTriggersCreateMultipleEntities() {
        int targetId = defineSelfCustomTarget();

        for (int i = 0; i < 3; i++) {
            given().log()
                    .all()
                    .when()
                    .pathParam("targetId", targetId)
                    .post("targets/{targetId}/gc")
                    .then()
                    .log()
                    .all()
                    .assertThat()
                    .statusCode(204);
        }

        long count = GarbageCollection.count("target.id = ?1", Long.valueOf(targetId));
        Assertions.assertEquals(
                3, count, "Expected three GarbageCollection entities to be created");
    }

    @Test
    public void testGcTriggerOnInvalidTargetFails() {
        given().log()
                .all()
                .when()
                .pathParam("targetId", Integer.MAX_VALUE)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);

        long count = GarbageCollection.count();
        Assertions.assertEquals(0, count, "No GarbageCollection entity should be created");
    }

    @Test
    @Transactional
    public void testGcEntityPersistence() {
        int targetId = defineSelfCustomTarget();
        Target target = Target.getTargetById(targetId);

        GarbageCollection gc = GarbageCollection.of(target);
        gc.persist();

        Assertions.assertNotNull(gc.id);
        Assertions.assertTrue(gc.id > 0);

        GarbageCollection found = GarbageCollection.findById(gc.id);
        Assertions.assertNotNull(found);
        Assertions.assertEquals(gc.id, found.id);
        Assertions.assertEquals(gc.triggeredAt, found.triggeredAt);
        Assertions.assertEquals(target.id, found.target.id);
    }

    @Test
    public void testGcAuditQueryIntegration() {
        int targetId = defineSelfCustomTarget();
        long startTime = System.currentTimeMillis();

        given().log()
                .all()
                .when()
                .pathParam("targetId", targetId)
                .post("targets/{targetId}/gc")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

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
                .body("entities.GarbageCollection", org.hamcrest.Matchers.notNullValue())
                .body(
                        "entities.GarbageCollection",
                        org.hamcrest.Matchers.instanceOf(java.util.List.class))
                .body(
                        "entities.GarbageCollection[0].triggeredAt",
                        org.hamcrest.Matchers.notNullValue())
                .body("entities.GarbageCollection[0].revtype", org.hamcrest.Matchers.equalTo(0));
    }
}
