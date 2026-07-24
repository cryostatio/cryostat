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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusIntegrationTest
public class PresetRulesIT {

    static final String[] RULE_NAMES = new String[] {"quarkus", "hibernate", "continuous_analysis"};

    @Test
    public void shouldListPresetRules() throws Exception {
        Response response =
                given().when().get("/api/v4/rules").then().statusCode(200).extract().response();

        JsonArray list = new JsonArray(response.body().asString());
        MatcherAssert.assertThat(list.size(), Matchers.equalTo(RULE_NAMES.length));
    }

    static List<String> ruleNames() {
        return Arrays.asList(RULE_NAMES);
    }

    @ParameterizedTest
    @MethodSource("ruleNames")
    public void shouldHavePresetRules(String ruleName) throws Exception {
        String url = String.format("/api/v4/rules/%s", ruleName);

        Response response =
                given().redirects()
                        .follow(true)
                        .when()
                        .get(url)
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        Path tempFile = Files.createTempFile(ruleName, ".json");
        Files.write(tempFile, response.asByteArray());
        File file = tempFile.toFile();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(file);

        MatcherAssert.assertThat(json.get("name").asText(), Matchers.equalTo(ruleName));
        MatcherAssert.assertThat(json.get("enabled").asBoolean(), Matchers.is(false));
    }
}
