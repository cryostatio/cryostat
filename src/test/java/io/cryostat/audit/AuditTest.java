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

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Audit.class)
public class AuditTest extends AbstractTransactionalTestBase {

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
                .body("name", Matchers.equalTo("Universe"))
                .body("nodeType", Matchers.equalTo("Universe"))
                .body("labels", Matchers.notNullValue())
                .body("children", Matchers.notNullValue())
                .body("children", Matchers.hasSize(Matchers.greaterThanOrEqualTo(1)))
                .body("target", Matchers.nullValue())
                .body("children[0].name", Matchers.notNullValue())
                .body("children[0].nodeType", Matchers.notNullValue())
                .body("children[0].labels", Matchers.notNullValue())
                .body("children[0].children", Matchers.notNullValue())
                .body("children[0].target", Matchers.nullValue());
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
}
