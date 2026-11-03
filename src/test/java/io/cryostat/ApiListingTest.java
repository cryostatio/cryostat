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
package io.cryostat;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.regex.Pattern;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ApiListingTest extends AbstractTestBase {

    ValidatableResponse resp;

    @BeforeEach
    void setup() {
        resp = given().log().all().accept(ContentType.JSON).when().get("/api").then();
    }

    @Test
    void shouldDefaultToYamlResponse() {
        given().log()
                .all()
                .when()
                .get("/api")
                .then()
                .assertThat()
                .contentType("application/yaml")
                .statusCode(200);
    }

    @Test
    void shouldBeOk() {
        resp.statusCode(200);
    }

    @Test
    void shouldRespondWithJson() {
        resp.contentType(ContentType.JSON);
    }

    @Test
    void shouldIncludeOpenApiInfo() {
        resp.body("openapi", Matchers.matchesRegex("^\\d+\\.\\d+\\.\\d+$"));
    }

    @Test
    void shouldIncludeApplicationInfo() {
        resp.body("info.title", Matchers.equalTo("Cryostat API"))
                .body("info.description", Matchers.equalTo("Cloud-Native JDK Flight Recorder"))
                .body("info.contact.name", Matchers.equalTo("Cryostat Community"))
                .body("info.contact.url", Matchers.equalTo("https://cryostat.io"))
                .body(
                        "info.contact.email",
                        Matchers.equalTo("cryostat-development@googlegroups.com"))
                .body("info.license.name", Matchers.equalTo("Apache 2.0"))
                .body(
                        "info.license.url",
                        Matchers.equalTo(
                                "https://github.com/cryostatio/cryostat/blob/main/LICENSE"))
                .body(
                        "info.version",
                        Matchers.matchesRegex(
                                Pattern.compile(
                                        "^\\d+\\.\\d+\\.\\d+(?:-snapshot)?$",
                                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)))
                .body("paths", Matchers.not(Matchers.blankOrNullString()));
    }

    @Test
    void shouldDescribeAsynchronousJobResponses() {
        var jobs =
                List.of(
                        new JobResponse("/api/v4/reports/{encodedKey}", "get", "202"),
                        new JobResponse(
                                "/api/v4/targets/{targetId}/reports/{recordingId}", "get", "202"),
                        new JobResponse("/api/v4.1/targets/{targetId}/reports", "post", "202"),
                        new JobResponse(
                                "/api/beta/diagnostics/targets/{jvmId}/heapdump/{heapDumpId}/analyze",
                                "post",
                                "202"),
                        new JobResponse("/api/beta/recording_synthesis/{jvmId}", "post", "202"),
                        new JobResponse(
                                "/api/v4/targets/{targetId}/recordings/{remoteId}", "patch", "200"),
                        new JobResponse(
                                "/api/v4/targets/{targetId}/recordings/{remoteId}/upload",
                                "post",
                                "200"),
                        new JobResponse("/api/v4/grafana/{encodedKey}", "post", "200"),
                        new JobResponse(
                                "/api/beta/diagnostics/targets/{targetId}/threaddump",
                                "post",
                                "200"),
                        new JobResponse(
                                "/api/beta/diagnostics/targets/{targetId}/heapdump",
                                "post",
                                "200"));
        for (var job : jobs) {
            String response =
                    "paths.'"
                            + job.path()
                            + "'."
                            + job.method()
                            + ".responses.'"
                            + job.status()
                            + "'";
            resp.body(response + ".description", Matchers.containsString("job ID"))
                    .body(
                            response + ".content.'text/plain'.schema.type",
                            Matchers.equalTo("string"))
                    .body(
                            response + ".content.'text/plain'.schema.format",
                            Matchers.equalTo("uuid"));
        }
    }

    @Test
    void shouldPreserveSynchronousAndAuthorizationResponses() {
        for (var job :
                List.of(
                        new JobResponse("/api/v4/reports/{encodedKey}", "get", "200"),
                        new JobResponse(
                                "/api/v4/targets/{targetId}/reports/{recordingId}", "get", "200"),
                        new JobResponse(
                                "/api/beta/diagnostics/targets/{jvmId}/heapdump/{heapDumpId}/analyze",
                                "post",
                                "200"),
                        new JobResponse("/api/beta/recording_synthesis/{jvmId}", "post", "200"))) {
            String responses = "paths.'" + job.path() + "'." + job.method() + ".responses";
            resp.body(
                            responses + ".'200'.content.'application/json'.schema",
                            Matchers.notNullValue())
                    .body(responses + ".'401'.description", Matchers.equalTo("Not Authorized"))
                    .body(responses + ".'403'.description", Matchers.equalTo("Not Allowed"));
        }
    }

    @Test
    void shouldOnlyAdvertiseAcceptedTargetAnalysis() {
        resp.body(
                "paths.'/api/v4.1/targets/{targetId}/reports'.post.responses.'200'",
                Matchers.nullValue());
    }

    private record JobResponse(String path, String method, String status) {}
}
