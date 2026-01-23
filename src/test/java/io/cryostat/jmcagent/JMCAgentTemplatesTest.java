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
package io.cryostat.jmcagent;

import static io.restassured.RestAssured.given;

import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(JMCAgentTemplates.class)
public class JMCAgentTemplatesTest extends AbstractTransactionalTestBase {

    @Test
    void testListNone() {
        MatcherAssert.assertThat(
                (List<?>)
                        given().log()
                                .all()
                                .when()
                                .get()
                                .then()
                                .assertThat()
                                .contentType(ContentType.JSON)
                                .statusCode(200)
                                .extract()
                                .body()
                                .as(List.class),
                Matchers.equalTo(List.of()));
    }

    @Test
    void testDeleteNone() {
        given().log().all().when().delete("/nothing").then().assertThat().statusCode(404);
    }

    @Test
    void testCreateAndDelete() {
        var filename = "agentTemplatesProbe1";
        given().log()
                .all()
                .when()
                .multiPart("name", filename)
                .multiPart(
                        "probeTemplate",
"""
<jfragent>
    <config>
        <classprefix>__JFREvent</classprefix>
        <allowtostring>true</allowtostring>
        <allowconverter>true</allowconverter>
    </config>
    <events>
        <event id="maxcao.jfr">
            <label>MaxCaoEvent</label>
            <description>Event for me Max</description>
            <class>io.cryostat.net.web.http.api.v1.RecordingsGetHandler</class>
            <path>maxcao</path>
            <stacktrace>true</stacktrace>
            <rethrow>false</rethrow>
            <location>ENTRY</location>
            <method>
                <name>handleAuthenticated</name>
                <descriptor>(Lio/vertx/ext/web/RoutingContext;)V</descriptor>
            </method>
        </event>
        <event id="demo.jfr.test1">
            <label>DemoEvent</label>
            <description>Event for the agent plugin demo</description>
            <class>io.cryostat.net.web.http.api.v1.TargetTemplatesGetHandler</class>
            <path>demo</path>
            <stacktrace>true</stacktrace>
            <rethrow>false</rethrow>
            <location>ENTRY</location>
            <method>
                <name>handleAuthenticated</name>
                <descriptor>(Lio/vertx/ext/web/RoutingContext;)V</descriptor>
            </method>
        </event>
    </events>
</jfragent>
""")
                .post()
                .then()
                .assertThat()
                .statusCode(201)
                .body(
                        "fileName",
                        Matchers.equalTo(filename),
                        "classPrefix",
                        Matchers.equalTo("__JFREvent"),
                        "allowToString",
                        Matchers.equalTo(true),
                        "allowConverter",
                        Matchers.equalTo(true),
                        "events.size()",
                        Matchers.equalTo(2),
                        "events[0].id",
                        Matchers.equalTo("maxcao.jfr"),
                        "events[0].name",
                        Matchers.equalTo("MaxCaoEvent"),
                        "events[0].clazz",
                        Matchers.equalTo("io.cryostat.net.web.http.api.v1.RecordingsGetHandler"),
                        "events[0].description",
                        Matchers.equalTo("Event for me Max"),
                        "events[0].path",
                        Matchers.equalTo("maxcao"),
                        "events[0].recordStackTrace",
                        Matchers.equalTo(true),
                        "events[0].useRethrow",
                        Matchers.equalTo(false),
                        "events[0].methodName",
                        Matchers.equalTo("handleAuthenticated"),
                        "events[0].methodDescriptor",
                        Matchers.equalTo("(Lio/vertx/ext/web/RoutingContext;)V"),
                        "events[0].location",
                        Matchers.equalTo("ENTRY"),
                        "events[0].returnValue",
                        Matchers.nullValue(),
                        "events[1].id",
                        Matchers.equalTo("demo.jfr.test1"),
                        "events[1].name",
                        Matchers.equalTo("DemoEvent"),
                        "events[1].clazz",
                        Matchers.equalTo(
                                "io.cryostat.net.web.http.api.v1.TargetTemplatesGetHandler"),
                        "events[1].description",
                        Matchers.equalTo("Event for the agent plugin demo"),
                        "events[1].path",
                        Matchers.equalTo("demo"),
                        "events[1].recordStackTrace",
                        Matchers.equalTo(true),
                        "events[1].useRethrow",
                        Matchers.equalTo(false),
                        "events[1].methodName",
                        Matchers.equalTo("handleAuthenticated"),
                        "events[1].methodDescriptor",
                        Matchers.equalTo("(Lio/vertx/ext/web/RoutingContext;)V"),
                        "events[1].location",
                        Matchers.equalTo("ENTRY"),
                        "events[1].returnValue",
                        Matchers.nullValue());

        given().log().all().when().delete(filename).then().assertThat().statusCode(204);
        given().log().all().when().delete(filename).then().assertThat().statusCode(404);
    }

    @Test
    void testDownloadNonExistent() {
        given().log().all().when().get("/nonexistent").then().assertThat().statusCode(404);
    }

    @Test
    void testCreateDownloadAndDelete() {
        var filename = "downloadTestProbe";
        var xmlContent =
                """
                <jfragent>
                    <config>
                        <classprefix>__JFREvent</classprefix>
                        <allowtostring>true</allowtostring>
                        <allowconverter>true</allowconverter>
                    </config>
                    <events>
                        <event id="download.test.jfr">
                            <label>DownloadTestEvent</label>
                            <description>Event for download test</description>
                            <class>io.cryostat.test.DownloadTestHandler</class>
                            <path>downloadtest</path>
                            <stacktrace>true</stacktrace>
                            <rethrow>false</rethrow>
                            <location>ENTRY</location>
                            <method>
                                <name>handleRequest</name>
                                <descriptor>(Ljava/lang/Object;)V</descriptor>
                            </method>
                        </event>
                    </events>
                </jfragent>
                """;

        // Create template
        given().log()
                .all()
                .when()
                .multiPart("name", filename)
                .multiPart("probeTemplate", xmlContent)
                .post()
                .then()
                .assertThat()
                .statusCode(201);

        // Download and verify
        var downloaded =
                given().log()
                        .all()
                        .when()
                        .get("/" + filename)
                        .then()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.XML)
                        .extract()
                        .body()
                        .asString();
        MatcherAssert.assertThat(downloaded, Matchers.containsString("<jfragent>"));

        // Cleanup
        given().log().all().when().delete("/" + filename).then().assertThat().statusCode(204);
    }
}
