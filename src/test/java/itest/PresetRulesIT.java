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

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.HttpRequest;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
public class PresetRulesIT extends StandardSelfTest {

    @Test
    public void shouldListPresetRules() throws Exception {
        CompletableFuture<JsonArray> future = new CompletableFuture<>();
        HttpRequest<Buffer> req = webClient.get("/api/v4/rules");
        req.send(
                ar -> {
                    if (ar.failed()) {
                        future.completeExceptionally(ar.cause());
                        return;
                    }
                    future.complete(ar.result().bodyAsJsonArray());
                });
        JsonArray response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(response.size(), Matchers.equalTo(1));
    }

    @Test
    public void shouldHavePresetQuarkusRule() throws Exception {
        String url = "/api/v4/rules/quarkus";
        File file =
                downloadFile(url, "quarkus", ".json")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .toFile();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(file);

        MatcherAssert.assertThat(json.get("name").asText(), Matchers.equalTo("quarkus"));
        MatcherAssert.assertThat(
                json.get("description").asText(),
                Matchers.equalTo(
                        "Preset Automated Rule for enabling Quarkus framework-specific events when"
                                + " available"));
        MatcherAssert.assertThat(
                json.get("eventSpecifier").asText(),
                Matchers.equalTo("template=Quarkus,type=PRESET"));
        MatcherAssert.assertThat(
                json.get("matchExpression").asText(),
                Matchers.equalTo("jfrEventTypeIds(target).exists(x, x.contains(\"quarkus\"))"));
        MatcherAssert.assertThat(json.get("enabled").asBoolean(), Matchers.is(false));
    }
}
