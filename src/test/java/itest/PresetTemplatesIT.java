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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusIntegrationTest
public class PresetTemplatesIT {

    static final String[] TEMPLATE_NAMES = new String[] {"Quarkus", "Hibernate"};

    @BeforeAll
    static void configureRestAssured() {
        int port = Integer.parseInt(System.getenv().getOrDefault("QUARKUS_HTTP_PORT", "8081"));
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    @Test
    public void shouldListPresetTemplates() throws Exception {
        Response response =
                given().when()
                        .get("/api/v4/event_templates/PRESET")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray list = new JsonArray(response.body().asString());
        MatcherAssert.assertThat(list.size(), Matchers.equalTo(TEMPLATE_NAMES.length));
    }

    static List<String> templateNames() {
        return Arrays.asList(TEMPLATE_NAMES);
    }

    @ParameterizedTest
    @MethodSource("templateNames")
    public void shouldHaveExpectedPresetTemplates(String templateName) throws Exception {
        String url = String.format("/api/v4/event_templates/PRESET/%s", templateName);

        Response response =
                given().redirects()
                        .follow(true)
                        .when()
                        .get(url)
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        Path tempFile = Files.createTempFile(templateName, ".jfc");
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

        Assertions.assertNotNull(labelAttr, "Label attribute is missing");

        String name = labelAttr.getExplicitValue();
        MatcherAssert.assertThat(name, Matchers.equalTo(templateName));
    }
}
