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

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(JMCAgentTemplates.class)
public class JMCAgentTemplatesTest extends AbstractTransactionalTestBase {

    @Test
    void testListNone() {
        given().log()
                .all()
                .when()
                .get()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testDeleteNone() {
        given().log().all().when().delete("/nothing").then().assertThat().statusCode(404);
    }

    @Test
    @Disabled("https://github.com/cryostatio/cryostat-core/issues/450")
    void testCreate() {
        given().log()
                .all()
                .when()
                .multiPart("name", "agentTemplatesProbe1")
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
                .body("size()", Matchers.equalTo(0));
    }
}
