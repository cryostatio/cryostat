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
package io.cryostat.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds an AsyncAPI 2.6.0 compliant schema from discovered notification sites. */
public class AsyncAPISchemaBuilder {

    private static final String ASYNCAPI_VERSION = "2.6.0";
    private static final String CRYOSTAT_VERSION = "4.2.0";

    public Map<String, Object> build(List<NotificationSite> notificationSites) {
        Map<String, Object> schema = new LinkedHashMap<>();

        // AsyncAPI version
        schema.put("asyncapi", ASYNCAPI_VERSION);

        // Info section
        schema.put("info", buildInfo());

        // Servers section
        schema.put("servers", buildServers());

        // Channels section
        schema.put("channels", buildChannels(notificationSites));

        // Components section
        schema.put("components", buildComponents(notificationSites));

        return schema;
    }

    private Map<String, Object> buildInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "Cryostat WebSocket Notifications");
        info.put("version", CRYOSTAT_VERSION);
        info.put(
                "description",
                "Real-time notifications emitted by Cryostat for various events including recording"
                        + " lifecycle, target discovery, diagnostic operations, and more. Clients"
                        + " connect to the WebSocket endpoint and receive notifications as JSON"
                        + " messages.");

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", "Cryostat Development Team");
        contact.put("url", "https://cryostat.io");
        contact.put("email", "cryostat-development@googlegroups.com");
        info.put("contact", contact);

        Map<String, Object> license = new LinkedHashMap<>();
        license.put("name", "Apache 2.0");
        license.put("url", "https://www.apache.org/licenses/LICENSE-2.0");
        info.put("license", license);

        return info;
    }

    private Map<String, Object> buildServers() {
        Map<String, Object> servers = new LinkedHashMap<>();

        Map<String, Object> production = new LinkedHashMap<>();
        production.put("url", "ws://localhost:8181/api/notifications");
        production.put("protocol", "ws");
        production.put("description", "WebSocket notification endpoint");

        servers.put("production", production);
        return servers;
    }

    private Map<String, Object> buildChannels(List<NotificationSite> notificationSites) {
        Map<String, Object> channels = new LinkedHashMap<>();

        Map<String, Object> notificationsChannel = new LinkedHashMap<>();
        notificationsChannel.put("description", "Subscribe to receive all Cryostat notifications");

        Map<String, Object> subscribe = new LinkedHashMap<>();
        subscribe.put("summary", "Subscribe to Cryostat notifications");
        subscribe.put(
                "description",
                "Clients connect to this WebSocket endpoint to receive real-time notifications "
                        + "about events occurring in Cryostat.");

        // Build message oneOf list
        Map<String, Object> message = new LinkedHashMap<>();
        List<Map<String, Object>> oneOf =
                notificationSites.stream()
                        .map(NotificationSite::getResolvedCategory)
                        .filter(category -> category != null && !category.startsWith("UNRESOLVED"))
                        .distinct()
                        .sorted()
                        .map(
                                category ->
                                        Map.<String, Object>of(
                                                "$ref", "#/components/messages/" + category))
                        .collect(Collectors.toList());

        if (!oneOf.isEmpty()) {
            message.put("oneOf", oneOf);
        }

        subscribe.put("message", message);
        notificationsChannel.put("subscribe", subscribe);

        channels.put("/api/notifications", notificationsChannel);
        return channels;
    }

    private Map<String, Object> buildComponents(List<NotificationSite> notificationSites) {
        Map<String, Object> components = new LinkedHashMap<>();

        // Build messages
        Map<String, Object> messages = new LinkedHashMap<>();

        // Group sites by category
        Map<String, List<NotificationSite>> sitesByCategory =
                notificationSites.stream()
                        .filter(
                                site ->
                                        site.getResolvedCategory() != null
                                                && !site.getResolvedCategory()
                                                        .startsWith("UNRESOLVED"))
                        .collect(Collectors.groupingBy(NotificationSite::getResolvedCategory));

        sitesByCategory.forEach(
                (category, sites) -> {
                    messages.put(category, buildMessage(category, sites.get(0)));
                });

        components.put("messages", messages);

        // Build schemas (if needed for complex payload types)
        Map<String, Object> schemas = new LinkedHashMap<>();
        components.put("schemas", schemas);

        return components;
    }

    private Map<String, Object> buildMessage(String category, NotificationSite site) {
        Map<String, Object> message = new LinkedHashMap<>();

        message.put("name", category);
        message.put("title", formatTitle(category));
        message.put("summary", "Notification: " + category);
        message.put(
                "description",
                String.format(
                        "Emitted from %s:%d",
                        site.getSourceFile().replaceAll(".*/src/main/java/", ""),
                        site.getLineNumber()));

        // Build payload schema
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "object");
        payload.put("description", "WebSocket notification message wrapper");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Meta property
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "object");
        meta.put("description", "Notification metadata");
        Map<String, Object> metaProps = new LinkedHashMap<>();
        Map<String, Object> categoryProp = new LinkedHashMap<>();
        categoryProp.put("type", "string");
        categoryProp.put("const", category);
        categoryProp.put("description", "Notification category identifier");
        metaProps.put("category", categoryProp);
        meta.put("properties", metaProps);
        meta.put("required", List.of("category"));
        properties.put("meta", meta);

        // Message property (the actual payload)
        Map<String, Object> messagePayload = site.getPayloadSchema();
        if (messagePayload != null && !messagePayload.isEmpty()) {
            properties.put("message", messagePayload);
        } else {
            Map<String, Object> defaultMessage = new LinkedHashMap<>();
            defaultMessage.put("type", "object");
            defaultMessage.put("description", "Notification payload");
            properties.put("message", defaultMessage);
        }

        payload.put("properties", properties);
        payload.put("required", List.of("meta", "message"));

        message.put("payload", payload);

        return message;
    }

    private String formatTitle(String category) {
        // Convert "ActiveRecordingCreated" to "Active Recording Created"
        return category.replaceAll("([A-Z])", " $1").trim();
    }
}
