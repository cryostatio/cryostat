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
package io.cryostat.discovery;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Discovery.class)
public class DiscoveryTest extends AbstractTransactionalTestBase {

    @Test
    void testGetUniverse() {
        given().log()
                .all()
                .when()
                .get("/api/v4/discovery")
                .then()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", Matchers.equalTo(1))
                .body("name", Matchers.equalTo("Universe"))
                .body("nodeType", Matchers.equalTo("Universe"))
                .body("labels", Matchers.equalTo(List.of()))
                .body("children.size()", Matchers.equalTo(5))
                .body("children", Matchers.everyItem(Matchers.hasEntry("nodeType", "Realm")))
                .body(
                        "children",
                        Matchers.hasItems(
                                Matchers.hasEntry("name", "Custom Targets"),
                                Matchers.hasEntry("name", "KubernetesApi"),
                                Matchers.hasEntry("name", "Podman"),
                                Matchers.hasEntry("name", "Docker"),
                                Matchers.hasEntry("name", "JDP")))
                .body("parent", Matchers.nullValue())
                .body("target", Matchers.nullValue());
    }

    @Test
    void getDiscoveryPlugins() {
        List<Map<String, String>> plugins =
                given().log()
                        .all()
                        .when()
                        .get("/api/v4/discovery_plugins")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .body("size()", Matchers.equalTo(5))
                        .extract()
                        .jsonPath()
                        .getList("$");
        MatcherAssert.assertThat(plugins, Matchers.hasSize(5));
        for (var plugin : plugins) {
            MatcherAssert.assertThat(
                    plugin,
                    Matchers.hasEntry(
                            Matchers.equalTo("id"), Matchers.not(Matchers.blankOrNullString())));
        }
    }

    // TODO write tests for discovery plugin registration process

}
