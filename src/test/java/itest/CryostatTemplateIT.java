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
package itest;

import static io.restassured.RestAssured.given;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import itest.bases.WebSocketTestBase;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
public class CryostatTemplateIT extends WebSocketTestBase {

    private static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
    private static final String SELFTEST_ALIAS = "selftest";
    private static volatile String selfCustomTargetLocation;

    @Test
    public void shouldHaveCryostatTemplate() throws Exception {
        String url =
                String.format(
                        "/api/v4/targets/%d/event_templates/TARGET/Cryostat",
                        getSelfReferenceTargetId());

        Response response =
                given().redirects()
                        .follow(true)
                        .when()
                        .get(url)
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        Path tempFile = Files.createTempFile("cryostat", ".jfc");
        Files.write(tempFile, response.asByteArray());
        File file = tempFile.toFile();

        XMLModel model = EventConfiguration.createModel(file);
        model.checkErrors();

        Assertions.assertFalse(model.hasErrors());

        XMLTagInstance configuration = model.getRoot();
        XMLAttributeInstance labelAttr = null;
        for (XMLAttributeInstance attr : configuration.getAttributeInstances()) {
            if (attr.getAttribute().getName().equals("label")) {
                labelAttr = attr;
                break;
            }
        }

        Assertions.assertNotNull(labelAttr, "Label attribute should not be null");
        String templateName = labelAttr.getExplicitValue();
        MatcherAssert.assertThat(templateName, Matchers.equalTo("Cryostat"));
    }

    private static long getSelfReferenceTargetId() {
        tryDefineSelfCustomTarget();
        String path = URI.create(selfCustomTargetLocation).getPath();
        Response response = given().when().get(path).then().statusCode(200).extract().response();
        JsonObject body = new JsonObject(response.body().asString());
        return body.getLong("id");
    }

    private static void tryDefineSelfCustomTarget() {
        if (selfCustomTargetExists()) {
            return;
        }
        JsonObject self =
                new JsonObject(Map.of("connectUrl", SELF_JMX_URL, "alias", SELFTEST_ALIAS));
        Response response =
                given().contentType(ContentType.JSON)
                        .body(self.encode())
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .extract()
                        .response();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(String.format("HTTP %d", response.statusCode()));
        }
        selfCustomTargetLocation = URI.create(response.header("Location")).getPath();
    }

    private static boolean selfCustomTargetExists() {
        if (StringUtils.isBlank(selfCustomTargetLocation)) {
            return false;
        }
        try {
            Response response = given().when().get(selfCustomTargetLocation);
            boolean result = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!result) {
                selfCustomTargetLocation = null;
            }
            return result;
        } catch (Exception e) {
            selfCustomTargetLocation = null;
            return false;
        }
    }
}
