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
package io.cryostat.events;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Objects;

import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.JFCGrammar;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLValidationResult;

import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.core.templates.TemplateType;

public abstract class AbstractFileBasedTemplateService implements TemplateService {

    XMLModel parseXml(InputStream inputStream) throws IOException, ParseException {
        try (inputStream) {
            var model = EventConfiguration.createModel(inputStream);
            model.checkErrors();

            for (XMLValidationResult result : model.getResults()) {
                if (result.isError()) {
                    throw new IllegalArgumentException(
                            new InvalidEventTemplateException(result.getText()));
                }
            }
            return model;
        }
    }

    Template createTemplate(XMLModel model) throws IOException, ParseException {
        XMLTagInstance configuration = model.getRoot();
        XMLAttributeInstance labelAttr = null;
        for (XMLAttributeInstance attr : configuration.getAttributeInstances()) {
            if (attr.getAttribute().getName().equals("label")) {
                labelAttr = attr;
                break;
            }
        }

        if (labelAttr == null) {
            throw new IllegalArgumentException(
                    new InvalidEventTemplateException(
                            "Template has no configuration label attribute"));
        }

        String templateName = labelAttr.getExplicitValue().replaceAll("[\\W]+", "_");

        XMLTagInstance root = model.getRoot();
        root.setValue(JFCGrammar.ATTRIBUTE_LABEL_MANDATORY, templateName);

        String description = getAttributeValue(root, "description");
        String provider = getAttributeValue(root, "provider");

        return new Template(templateName, description, provider, TemplateType.CUSTOM);
    }

    protected String getAttributeValue(XMLTagInstance node, String valueKey) {
        return node.getAttributeInstances().stream()
                .filter(i -> Objects.equals(valueKey, i.getAttribute().getName()))
                .map(i -> i.getValue())
                .findFirst()
                .get();
    }
}
