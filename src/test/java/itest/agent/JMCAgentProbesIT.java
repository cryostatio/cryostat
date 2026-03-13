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
package itest.agent;

import static io.restassured.RestAssured.given;

import java.util.List;

import io.cryostat.resources.AgentExternalRecordingApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(
        value = AgentExternalRecordingApplicationResource.class,
        restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class JMCAgentProbesIT extends AgentTestBase {

    static final String PROBE_TEMPLATE_NAME = "agentProbesTestTemplate";
    static final String PROBE_TEMPLATE_XML =
            """
            <jfragent>
                <config>
                    <classprefix>__JFREvent</classprefix>
                    <allowtostring>true</allowtostring>
                    <allowconverter>true</allowconverter>
                </config>
                <events>
                    <event id="test.probe.event1">
                        <label>TestProbeEvent1</label>
                        <description>Test probe event 1</description>
                        <class>io.cryostat.Cryostat</class>
                        <path>test/probe1</path>
                        <stacktrace>true</stacktrace>
                        <rethrow>false</rethrow>
                        <location>ENTRY</location>
                        <method>
                            <name>main</name>
                            <descriptor>([Ljava/lang/String;)V</descriptor>
                        </method>
                    </event>
                    <event id="test.probe.event2">
                        <label>TestProbeEvent2</label>
                        <description>Test probe event 2</description>
                        <class>io.cryostat.Cryostat</class>
                        <path>test/probe2</path>
                        <stacktrace>false</stacktrace>
                        <rethrow>false</rethrow>
                        <location>ENTRY</location>
                        <method>
                            <name>main</name>
                            <descriptor>([Ljava/lang/String;)V</descriptor>
                        </method>
                    </event>
                </events>
            </jfragent>
            """;

    @BeforeEach
    void uploadProbeTemplate() {
        given().log()
                .all()
                .when()
                .multiPart("name", PROBE_TEMPLATE_NAME)
                .multiPart("probeTemplate", PROBE_TEMPLATE_XML)
                .post("/api/v4/probes")
                .then()
                .assertThat()
                .statusCode(201);
    }

    @AfterEach
    void cleanupProbeTemplate() {
        given().log()
                .all()
                .when()
                .delete("/api/v4/probes/" + PROBE_TEMPLATE_NAME)
                .then()
                .assertThat()
                .statusCode(Matchers.either(Matchers.is(204)).or(Matchers.is(404)));
    }

    @Test
    void testListNoProbes() {
        MatcherAssert.assertThat(
                (List<?>)
                        given().log()
                                .all()
                                .pathParams("targetId", target.id())
                                .when()
                                .get("/api/v4/targets/{targetId}/probes")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .contentType(ContentType.JSON)
                                .statusCode(200)
                                .extract()
                                .body()
                                .as(List.class),
                Matchers.equalTo(List.of()));
    }

    @Test
    void testInsertAndRemoveProbes() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "probeTemplateName", PROBE_TEMPLATE_NAME)
                .when()
                .post("/api/v4/targets/{targetId}/probes/{probeTemplateName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/probes")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(2))
                .and()
                .body("[0].name", Matchers.equalTo("TestProbeEvent1"))
                .body("[0].description", Matchers.equalTo("Test probe event 1"))
                .and()
                .body("[1].name", Matchers.equalTo("TestProbeEvent2"))
                .body("[1].description", Matchers.equalTo("Test probe event 2"));

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .delete("/api/v4/targets/{targetId}/probes")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        testListNoProbes();
    }

    @Test
    void testInsertProbesWithNonExistentTemplate() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "probeTemplateName", "nonexistent")
                .when()
                .post("/api/v4/targets/{targetId}/probes/{probeTemplateName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testRemoveProbesWhenNoneActive() {
        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .delete("/api/v4/targets/{targetId}/probes")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testInsertProbesMultipleTimes() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "probeTemplateName", PROBE_TEMPLATE_NAME)
                .when()
                .post("/api/v4/targets/{targetId}/probes/{probeTemplateName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .pathParams("targetId", target.id(), "probeTemplateName", PROBE_TEMPLATE_NAME)
                .when()
                .post("/api/v4/targets/{targetId}/probes/{probeTemplateName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/probes")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(2));

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .delete("/api/v4/targets/{targetId}/probes")
                .then()
                .assertThat()
                .statusCode(204);
    }
}
