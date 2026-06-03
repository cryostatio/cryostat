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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AuditCacheControlFilterTest.class)
@TestHTTPEndpoint(Audit.class)
public class AuditCacheControlFilterTest extends AuditTestBase {

    @Test
    public void testTargetByJvmIdHasCacheControlHeaders() {
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
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }

    @Test
    public void testTargetLineageByJvmIdHasCacheControlHeaders() {
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
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }

    @Test
    public void testGetRevisionsHasCacheControlHeaders() {
        given().log()
                .all()
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }

    @Test
    public void testGetRevisionDetailHasCacheControlHeaders() {
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

        given().log()
                .all()
                .when()
                .get("revisions/{rev}", revisionNumber)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }

    @Test
    public void testNotFoundResponsesAlsoHaveCacheControlHeaders() {
        given().log()
                .all()
                .when()
                .get("targets/{jvmId}", "nonexistent-jvm-id")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(404)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }

    @Test
    public void testBadRequestResponsesAlsoHaveCacheControlHeaders() {
        given().log()
                .all()
                .queryParam("page", -1)
                .when()
                .get("revisions")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0");
    }
}
