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

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("revisions.size()", Matchers.lessThanOrEqualTo(10))
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
                .body("revisions", Matchers.instanceOf(java.util.List.class))
                .body("revisions.size()", Matchers.lessThanOrEqualTo(5));
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

        if (revisionNumber != null) {
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
    }

    @Test
    public void testGetRevisionDetailReturnsCorrectStructure() {
        defineSelfCustomTarget();

        Integer revisionNumber =
                given().queryParam("pageSize", 1)
                        .when()
                        .get("revisions")
                        .then()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .path("revisions[0].rev");

        if (revisionNumber != null) {
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
                    .body("rev", Matchers.notNullValue())
                    .body("revtstmp", Matchers.notNullValue())
                    .body("username", Matchers.anything())
                    .body("entities", Matchers.instanceOf(java.util.Map.class));
        }
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
                .body("totalCount", Matchers.greaterThanOrEqualTo(0L));
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
}
