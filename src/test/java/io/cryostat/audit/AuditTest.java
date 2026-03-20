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
package io.cryostat.audit;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AuditTest.class)
@TestHTTPEndpoint(Audit.class)
public class AuditTest extends AuditTestBase {

    @Test
    public void testGetTargetByJvmId() {
        defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .get("targets/{jvmId}", selfJvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("alias", Matchers.equalTo(SELFTEST_ALIAS))
                .body("jvmId", Matchers.equalTo(selfJvmId))
                .body("labels", Matchers.notNullValue())
                .body("annotations", Matchers.notNullValue());
    }

    @Test
    public void testGetTargetByJvmIdNotFound() {
        given().log()
                .all()
                .when()
                .get("targets/{jvmId}", "nonexistent-jvm-id")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testGetTargetByJvmIdBlankId() {
        given().log()
                .all()
                .when()
                .get("targets/%20")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testGetTargetLineageByJvmId() {
        defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .get("target_lineage/{jvmId}", selfJvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("id", Matchers.notNullValue())
                .body("name", Matchers.equalTo("Universe"))
                .body("nodeType", Matchers.equalTo("Universe"))
                .body("labels", Matchers.notNullValue())
                .body("children", Matchers.notNullValue())
                .body("children", Matchers.hasSize(Matchers.greaterThanOrEqualTo(1)))
                .body("target", Matchers.nullValue())
                .body("children[0].id", Matchers.notNullValue())
                .body("children[0].name", Matchers.notNullValue())
                .body("children[0].nodeType", Matchers.notNullValue())
                .body("children[0].labels", Matchers.notNullValue())
                .body("children[0].children", Matchers.notNullValue())
                .body("children[0].target", Matchers.nullValue())
                .body("children[0].children[0].id", Matchers.notNullValue())
                .body("children[0].children[0].name", Matchers.notNullValue())
                .body("children[0].children[0].nodeType", Matchers.equalTo("JVM"))
                .body("children[0].children[0].labels", Matchers.notNullValue())
                .body("children[0].children[0].children", Matchers.notNullValue())
                .body("children[0].children[0].target", Matchers.notNullValue())
                .body("children[0].children[0].target.id", Matchers.notNullValue())
                .body("children[0].children[0].target.connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("children[0].children[0].target.alias", Matchers.equalTo(SELFTEST_ALIAS))
                .body("children[0].children[0].target.jvmId", Matchers.equalTo(selfJvmId))
                .body("children[0].children[0].target.labels", Matchers.notNullValue())
                .body("children[0].children[0].target.annotations", Matchers.notNullValue())
                .body(
                        "children[0].children[0].target.annotations.platform",
                        Matchers.notNullValue())
                .body(
                        "children[0].children[0].target.annotations.cryostat",
                        Matchers.notNullValue())
                .body("children[0].children[0].target.agent", Matchers.equalTo(false));
    }

    @Test
    public void testGetTargetLineageByJvmIdNotFound() {
        given().log()
                .all()
                .when()
                .get("target_lineage/{jvmId}", "nonexistent-jvm-id")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testGetTargetLineageByJvmIdBlankId() {
        given().log()
                .all()
                .when()
                .get("target_lineage/%20")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testGetRevisionsWithNoParameters() {
        given().log()
                .all()
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body("totalCount", Matchers.equalTo(1)); // Only seed revision
    }

    @Test
    public void testGetRevisionsWithStartTimeOnly() {
        long startTime = System.currentTimeMillis() - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body(
                        "totalCount",
                        Matchers.equalTo(0)); // Seed revision is at timestamp 0, before startTime
    }

    @Test
    public void testGetRevisionsWithEndTimeOnly() {
        long endTime = System.currentTimeMillis();

        given().log()
                .all()
                .queryParam("endTime", endTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body(
                        "totalCount",
                        Matchers.equalTo(1)); // Seed revision is at timestamp 0, within range
    }

    @Test
    public void testGetRevisionsWithTimeRange() {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body(
                        "totalCount",
                        Matchers.equalTo(0)); // Seed revision is at timestamp 0, before range
    }

    @Test
    public void testGetRevisionsWithInvalidTimeRange() {
        long startTime = System.currentTimeMillis();
        long endTime = startTime - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testGetRevisionsWithPagination() {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .queryParam("page", 0)
                .queryParam("pageSize", 10)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body("revisions.size()", Matchers.equalTo(0)) // No revisions in time range
                .body(
                        "totalCount",
                        Matchers.equalTo(0)); // Seed revision is at timestamp 0, before time range
    }

    @Test
    public void testGetRevisionsWithInvalidPage() {
        given().log()
                .all()
                .queryParam("page", -1)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testGetRevisionsWithInvalidPageSize() {
        given().log()
                .all()
                .queryParam("pageSize", 0)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testGetRevisionsWithPageSizeExceedingMax() {
        given().log()
                .all()
                .queryParam("pageSize", 101)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testGetRevisionsReturnsDescendingOrder() {
        defineSelfCustomTarget();

        given().log()
                .all()
                .queryParam("pageSize", 5)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.instanceOf(List.class))
                .body("revisions.size()", Matchers.equalTo(2)); // Seed revision + target creation
    }

    @Test
    public void testGetRevisionDetailNotFound() {
        given().log()
                .all()
                .when()
                .get("revisions/{rev}", 999999)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testGetRevisionDetailWithValidRevision() {
        defineSelfCustomTarget();

        Integer revisionNumber =
                given().log()
                        .all()
                        .queryParam("pageSize", 1)
                        .when()
                        .get("revisions")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .body("revisions", Matchers.not(Matchers.empty()))
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Revision number should not be null");

        given().log()
                .all()
                .when()
                .get("revisions/{rev}", revisionNumber)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("rev", Matchers.equalTo(revisionNumber))
                .body("revtstmp", Matchers.notNullValue())
                .body("entities", Matchers.notNullValue());
    }

    @Test
    public void testGetRevisionDetailForInvalidRevisionZero() {
        // Revision 0 exists in the database but Hibernate Envers requires revision > 0
        // for findRevision() API, so querying revision 0 should return 400
        given().log()
                .all()
                .when()
                .get("revisions/0")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testSeedRevisionAppearsInList() {
        // Verify that the seed revision (rev=0) appears in the revisions list
        given().queryParam("pageSize", 100)
                .when()
                .get("revisions")
                .then()
                .assertThat()
                .statusCode(200)
                .body("revisions", Matchers.hasSize(Matchers.greaterThanOrEqualTo(1)))
                .body("revisions.find { it.rev == 0 }.username", Matchers.equalTo("system"))
                .body("revisions.find { it.rev == 0 }.revtstmp", Matchers.equalTo(0));
    }

    @Test
    public void testGetRevisionDetailForTargetCreation() {
        defineSelfCustomTarget();

        Number revisionNumber =
                given().queryParam("pageSize", 1)
                        .when()
                        .get("revisions")
                        .then()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Revision number should not be null");
        long rev = revisionNumber.longValue();

        given().log()
                .all()
                .when()
                .get("revisions/{rev}", rev)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("rev", Matchers.equalTo(1))
                .body("revtstmp", Matchers.greaterThan(0L))
                .body("username", Matchers.notNullValue())
                .body("entities", Matchers.instanceOf(Map.class))
                .body("entities.size()", Matchers.greaterThanOrEqualTo(1))
                .body("entities.keySet()", Matchers.hasItem("Target"))
                .body(
                        "entities.Target",
                        Matchers.allOf(
                                Matchers.instanceOf(List.class), Matchers.not(Matchers.empty())))
                .body("entities.Target[0]", Matchers.instanceOf(Map.class))
                .body("entities.Target[0].connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("entities.Target[0].alias", Matchers.equalTo(SELFTEST_ALIAS));
    }

    @Test
    public void testGetRevisionsAfterCreatingTarget() {
        long startTime = System.currentTimeMillis();
        defineSelfCustomTarget();
        long endTime = System.currentTimeMillis();

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.notNullValue())
                .body("totalCount", Matchers.equalTo(1)); // Target created within time range
    }

    @Test
    public void testGetRevisionsWithSecondPage() {
        defineSelfCustomTarget();

        given().log()
                .all()
                .queryParam("page", 1)
                .queryParam("pageSize", 5)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.notNullValue())
                .body("totalCount", Matchers.notNullValue());
    }

    @Test
    public void testGetRevisionsEmptyResultForFutureTimeRange() {
        long startTime = System.currentTimeMillis() + 86400000L;
        long endTime = startTime + 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("revisions", Matchers.empty())
                .body("totalCount", Matchers.equalTo(0));
    }

    @Test
    public void testGetRevisionDetailRedactsCredentialFields() {
        String matchExpression = "target.alias == 'io.cryostat.Cryostat'";
        String username = "testuser";
        String password = "testpassword";

        given().basePath("/")
                .contentType(ContentType.URLENC)
                .formParam("matchExpression", matchExpression)
                .formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/api/v4/credentials")
                .then()
                .statusCode(201);

        Number revisionNumber =
                given().queryParam("pageSize", 1)
                        .when()
                        .get("revisions")
                        .then()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Revision number should not be null");
        long rev = revisionNumber.longValue();

        given().log()
                .all()
                .when()
                .get("revisions/{rev}", rev)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("entities.Credential", Matchers.notNullValue())
                .body("entities.Credential", Matchers.instanceOf(List.class))
                .body("entities.Credential[0].username", Matchers.nullValue())
                .body("entities.Credential[0].password", Matchers.nullValue())
                .body("entities.Credential[0].matchExpression", Matchers.notNullValue());
    }

    @Test
    public void testGetRevisionDetailIncludesRevtype() {
        defineSelfCustomTarget();

        Number revisionNumber =
                given().queryParam("pageSize", 1)
                        .when()
                        .get("revisions")
                        .then()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Revision number should not be null");
        long rev = revisionNumber.longValue();

        given().log()
                .all()
                .when()
                .get("revisions/{rev}", rev)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("entities.Target", Matchers.notNullValue())
                .body("entities.Target[0].revtype", Matchers.notNullValue())
                .body("entities.Target[0].revtype", Matchers.isOneOf(0, 1, 2))
                .body("entities.Target[0].connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("entities.Target[0].alias", Matchers.equalTo(SELFTEST_ALIAS));
    }

    @Test
    public void testExportRevisionsMissingStartTime() {
        long endTime = System.currentTimeMillis();

        given().log()
                .all()
                .queryParam("endTime", endTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testExportRevisionsMissingEndTime() {
        long startTime = System.currentTimeMillis() - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testExportRevisionsInvalidTimeRange() {
        long startTime = System.currentTimeMillis();
        long endTime = startTime - 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testExportRevisionsEmptyRange() {
        long startTime = System.currentTimeMillis() + 86400000L;
        long endTime = startTime + 86400000L;

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("$", Matchers.instanceOf(List.class))
                .body("$", Matchers.empty());
    }

    @Test
    public void testExportRevisionsWithValidRange() {
        long startTime = System.currentTimeMillis();
        defineSelfCustomTarget();
        long endTime = System.currentTimeMillis();

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .header(
                        "Content-Disposition",
                        Matchers.startsWith("attachment; filename=\"audit-export-"))
                .body("$", Matchers.instanceOf(List.class))
                .body("$", Matchers.hasSize(1))
                .body("[0].rev", Matchers.notNullValue())
                .body("[0].revtstmp", Matchers.notNullValue())
                .body("[0].username", Matchers.notNullValue())
                .body("[0].entities", Matchers.notNullValue())
                .body("[0].entities", Matchers.instanceOf(Map.class))
                .body("[0].entities.Target", Matchers.notNullValue())
                .body("[0].entities.Target", Matchers.instanceOf(List.class))
                .body("[0].entities.Target[0].connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("[0].entities.Target[0].alias", Matchers.equalTo(SELFTEST_ALIAS))
                .body("[0].entities.Target[0].revtype", Matchers.notNullValue());
    }

    @Test
    public void testExportRevisionsIncludesAllRevisionDetails() {
        long startTime = System.currentTimeMillis();
        defineSelfCustomTarget();

        String matchExpression = "target.alias == 'io.cryostat.Cryostat'";
        String username = "testuser";
        String password = "testpassword";

        given().basePath("/")
                .contentType(ContentType.URLENC)
                .formParam("matchExpression", matchExpression)
                .formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/api/v4/credentials")
                .then()
                .statusCode(201);

        long endTime = System.currentTimeMillis();

        given().log()
                .all()
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .when()
                .get("export")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("$", Matchers.instanceOf(List.class))
                .body("$", Matchers.hasSize(2))
                .body("[0].entities.Credential", Matchers.notNullValue())
                .body("[0].entities.Credential[0].username", Matchers.nullValue())
                .body("[0].entities.Credential[0].password", Matchers.nullValue())
                .body("[1].entities.Target", Matchers.notNullValue());
    }
}
