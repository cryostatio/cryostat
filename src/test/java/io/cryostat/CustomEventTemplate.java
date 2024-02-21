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
package io.cryostat;

import static io.cryostat.TestUtils.givenBasicAuth;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;

import jakarta.transaction.Transactional;
import org.apache.http.entity.ContentType;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class CustomEventTemplate {

    Template template;

    static String TEMPLATE_NAME = "customEventTemplate";

    @BeforeEach
    public void setup() {
        template = new Template(TEMPLATE_NAME, "desc", "prov", TemplateType.CUSTOM);
    }

    @AfterEach
    @Transactional
    public void afterEach() {
        template.deleteAll();
    }

    @Test
    public void testCustomEventTemplate() {
        givenBasicAuth().get().then().statusCode(200);
    }

    @Test
    public void testCustom() {
        givenBasicAuth()
                .body()
                .contentType(ContentType.APPLICATION_XML)
                .post()
                .then()
                .statusCode(200);
    }
}
