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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;

import io.cryostat.ConfigProperties;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

@ApplicationScoped
class FileSystemTemplateService extends AbstractFileBasedTemplateService {

    @ConfigProperty(name = ConfigProperties.TEMPLATES_DIR)
    Path dir;

    @Inject Logger logger;

    private final Map<Template, Path> templatePaths = new HashMap<>();

    void onStart(@Observes StartupEvent evt) {
        if (!checkDir()) {
            return;
        }
        try {
            Files.walk(dir)
                    .forEach(
                            p -> {
                                try (var is = Files.newInputStream(p)) {
                                    var template = (createTemplate(parseXml(is)));
                                    templatePaths.put(template, p);
                                } catch (IOException | ParseException e) {
                                    logger.warn(e);
                                }
                            });
        } catch (IOException e) {
            logger.warn(e);
        }
    }

    public boolean hasTemplate(String name) {
        return templatePaths.keySet().stream().anyMatch(t -> t.getName().equals(name));
    }

    @Override
    public List<Template> getTemplates() {
        return new ArrayList<>(templatePaths.keySet());
    }

    @Override
    public Optional<Document> getXml(String templateName, TemplateType type) {
        if (!checkDir() || !TemplateType.CUSTOM.equals(type)) {
            return Optional.empty();
        }
        try (var stream = getModel(templateName)) {
            Document doc =
                    Jsoup.parse(stream, StandardCharsets.UTF_8.name(), "", Parser.xmlParser());
            return Optional.of(doc);
        } catch (IOException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<IConstrainedMap<EventOptionID>> getEvents(
            String templateName, TemplateType type) {
        if (!checkDir() || !TemplateType.CUSTOM.equals(type)) {
            return Optional.empty();
        }
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

    private InputStream getModel(String templateName) throws IOException {
        var entry =
                templatePaths.entrySet().stream()
                        .filter(e -> e.getKey().getName().equals(templateName))
                        .findFirst()
                        .orElseThrow();
        return Files.newInputStream(entry.getValue());
    }

    private boolean checkDir() {
        return Files.exists(dir)
                && Files.isReadable(dir)
                && Files.isExecutable(dir)
                && Files.isDirectory(dir);
    }
}
