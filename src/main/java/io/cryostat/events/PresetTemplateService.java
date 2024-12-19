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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.JFCGrammar;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLValidationResult;

import io.cryostat.ConfigProperties;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

@ApplicationScoped
public class PresetTemplateService implements TemplateService {

    @ConfigProperty(name = ConfigProperties.PRESET_TEMPLATES_DIR)
    Path dir;

    @Inject Logger logger;
    @Inject FileSystem fs;

    private final Map<String, Path> map = new HashMap<>();

    void onStart(@Observes StartupEvent evt) throws IOException {
        if (!checkDir()) {
            return;
        }
        Files.walk(dir)
                .filter(Files::isRegularFile)
                .filter(Files::isReadable)
                .forEach(
                        p -> {
                            try {
                                Template template = convertObject(p);
                                map.put(template.getName(), p);
                            } catch (InvalidEventTemplateException
                                    | IOException
                                    | ParseException e) {
                                logger.error(e);
                            }
                        });
    }

    private boolean checkDir() {
        return Files.exists(dir)
                && Files.isReadable(dir)
                && Files.isExecutable(dir)
                && Files.isDirectory(dir);
    }

    @Override
    public Optional<IConstrainedMap<EventOptionID>> getEvents(
            String templateName, TemplateType unused) throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            return Optional.of(
                    new EventConfiguration(parseXml(stream))
                            .getEventOptions(
                                    new SimpleConstrainedMap<>(
                                            UnitLookup.PLAIN_TEXT.getPersister())));
        } catch (IOException | ParseException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    @Override
    public List<Template> getTemplates() throws FlightRecorderException {
        return getObjects().stream()
                .map(
                        t -> {
                            try {
                                return convertObject(t);
                            } catch (InvalidEventTemplateException
                                    | ParseException
                                    | IOException e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<String> getXml(String templateName, TemplateType unused)
            throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            return Optional.of(
                    Jsoup.parse(stream, StandardCharsets.UTF_8.name(), "", Parser.xmlParser())
                            .outerHtml());
        } catch (IOException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    private InputStream getModel(String name) throws IOException {
        return Files.newInputStream(map.get(name));
    }

    private Collection<Path> getObjects() {
        return map.values();
    }

    private Template convertObject(Path file)
            throws InvalidEventTemplateException, IOException, ParseException {
        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(file))) {
            XMLModel model = parseXml(bis);
            return createTemplate(model);
        }
    }

    private Template createTemplate(XMLModel model) throws IOException, ParseException {
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

        return new Template(templateName, description, provider, TemplateType.PRESET);
    }

    private String getAttributeValue(XMLTagInstance node, String valueKey) {
        return node.getAttributeInstances().stream()
                .filter(i -> Objects.equals(valueKey, i.getAttribute().getName()))
                .map(i -> i.getValue())
                .findFirst()
                .get();
    }

    private XMLModel parseXml(InputStream inputStream) throws IOException, ParseException {
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
}
