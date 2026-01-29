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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.HttpRequest;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusIntegrationTest
public class PresetTemplatesIT extends StandardSelfTest {

    static final String[] TEMPLATE_NAMES = new String[] {"Quarkus", "Hibernate"};

    @Test
    public void shouldListPresetTemplates() throws Exception {
        CompletableFuture<JsonArray> future = new CompletableFuture<>();
        HttpRequest<Buffer> req = webClient.get("/api/v4/event_templates/PRESET");
        req.send(
                ar -> {
                    if (ar.failed()) {
                        future.completeExceptionally(ar.cause());
                        return;
                    }
                    future.complete(ar.result().bodyAsJsonArray());
                });
        JsonArray response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(response.size(), Matchers.equalTo(TEMPLATE_NAMES.length));
    }

    static List<String> templateNames() {
        return Arrays.asList(TEMPLATE_NAMES);
    }

    @ParameterizedTest
    @MethodSource("templateNames")
    public void shouldHaveExpectedPresetTemplates(String templateName) throws Exception {
        String url = String.format("/api/v4/event_templates/PRESET/%s", templateName);
        File file =
                downloadFile(url, templateName, ".jfc")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .toFile();

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
