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
import java.util.concurrent.TimeUnit;

import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
public class CryostatTemplateIT extends StandardSelfTest {

    @Test
    public void shouldHaveCryostatTemplate() throws Exception {
        String url =
                String.format(
                        "/api/v3/targets/%d/event_templates/TARGET/Cryostat",
                        getSelfReferenceTargetId());
        File file =
                downloadFile(url, "cryostat", ".jfc")
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

        MatcherAssert.assertThat(labelAttr, Matchers.notNullValue());

        String templateName = labelAttr.getExplicitValue();
        MatcherAssert.assertThat(templateName, Matchers.equalTo("Cryostat"));
    }
}
