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

import java.util.Base64;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class HeapDumpsTest extends AbstractTransactionalTestBase {

    @Test
    public void testListNone() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .get("/api/v5/targets/{jvmId}/diagnostics/heapdump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    public void testListInvalid() {
        given().log()
                .all()
                .when()
                .pathParam("jvmId", "nonexistent")
                .get("/api/v5/targets/{jvmId}/diagnostics/heapdump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .body("$.size()", Matchers.equalTo(0));
    }

    @Test
    public void testDeleteInvalid() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("heapDumpId", "foo")
                .delete("/api/v5/targets/{jvmId}/diagnostics/heapdump/{heapDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testDownloadInvalid() {
        String encodedKey = "abcd1234";
        given().log()
                .all()
                .when()
                .pathParam("encodedKey", encodedKey)
                .get("/api/v5/diagnostics/heapdump/download/{encodedKey}")
                .then()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testDownloadNotFound() {
        String encodedKey =
                Base64.getEncoder().encodeToString(String.format("nonexistent/file").getBytes());
        given().log()
                .all()
                .when()
                .pathParam("encodedKey", encodedKey)
                .get("/api/v5/diagnostics/heapdump/download/{encodedKey}")
                .then()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testAnalyzeInvalidTarget() {
        given().log()
                .all()
                .when()
                .pathParam("jvmId", "bar")
                .pathParam("heapDumpId", "foo")
                .post("/api/v5/targets/{jvmId}/diagnostics/heapdump/{heapDumpId}/analyze")
                .then()
                .assertThat()
                .statusCode(404);
    }
}
