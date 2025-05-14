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
package io.cryostat.targets;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IConstraint;
import org.openjdk.jmc.common.unit.IMutableConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.events.IEventTypeID;
import org.openjdk.jmc.flightrecorder.configuration.events.IEventTypeInfo;
import org.openjdk.jmc.flightrecorder.configuration.internal.EventTypeIDV2;

import io.cryostat.ConfigProperties;
import io.cryostat.core.serialization.JmcSerializableRecordingDescriptor;
import io.cryostat.credentials.Credential;
import io.cryostat.discovery.DiscoveryPlugin;
import io.cryostat.libcryostat.net.MBeanMetrics;
import io.cryostat.targets.AgentJFRService.StartRecordingRequest;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.codec.BodyCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.ForbiddenException;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

public class AgentClient {

    public static final String NULL_CREDENTIALS = "No credentials found for agent";

    @ConfigProperty(name = ConfigProperties.AGENT_TLS_REQUIRED)
    private boolean tlsRequired;

    private final Target target;
    private final WebClient webClient;
    private final Duration httpTimeout;
    private final ObjectMapper mapper;
    private final Logger logger = Logger.getLogger(getClass());

    private AgentClient(
            Target target, WebClient webClient, ObjectMapper mapper, Duration httpTimeout) {
        this.target = target;
        this.webClient = webClient;
        this.mapper = mapper;
        this.httpTimeout = httpTimeout;
    }

    Target getTarget() {
        return target;
    }

    URI getUri() {
        return getTarget().connectUrl;
    }

    Duration getTimeout() {
        return httpTimeout;
    }

    Uni<Boolean> ping() {
        return invoke(HttpMethod.GET, "/", BodyCodec.none())
                .map(HttpResponse::statusCode)
                .map(HttpStatusCodeIdentifier::isSuccessCode);
    }

    Uni<MBeanMetrics> mbeanMetrics() {
        return invoke(HttpMethod.GET, "/mbean-metrics/", BodyCodec.string())
                .map(HttpResponse::body)
                .map(Unchecked.function(s -> mapper.readValue(s, MBeanMetrics.class)));
    }

    <T> Uni<T> invokeMBeanOperation(
            String beanName,
            String operation,
            Object[] parameters,
            String[] signature,
            Class<T> returnType) {
        try {
            var req = new MBeanInvocationRequest(beanName, operation, parameters, signature);
            return invoke(
                            HttpMethod.POST,
                            "/mbean-invoke/",
                            Buffer.buffer(mapper.writeValueAsBytes(req)),
                            BodyCodec.buffer())
                    .map(
                            Unchecked.function(
                                    resp -> {
                                        int statusCode = resp.statusCode();
                                        if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                            return resp;
                                        } else if (statusCode == 403) {
                                            logger.errorv(
                                                    "invokeMBeanOperation {0} ({1}) for {2} failed:"
                                                            + " HTTP 403",
                                                    beanName, operation, getUri());
                                            throw new ForbiddenException(
                                                    new UnsupportedOperationException(
                                                            "startRecording"));
                                        } else {
                                            logger.errorv(
                                                    "invokeMBeanOperation for {0} for ({1}) {2}"
                                                            + " failed: HTTP {3}",
                                                    beanName, operation, getUri(), statusCode);
                                            throw new AgentApiException(statusCode);
                                        }
                                    }))
                    .map(HttpResponse::bodyAsBuffer)
                    .map(
                            buff -> {
                                // TODO implement conditional handling based on expected returnType
                                return null;
                            });
        } catch (JsonProcessingException e) {
            logger.error("invokeMBeanOperation request failed", e);
            return Uni.createFrom().failure(e);
        }
    }

    Uni<IRecordingDescriptor> startRecording(StartRecordingRequest req) {
        try {
            return invoke(
                            HttpMethod.POST,
                            "/recordings/",
                            Buffer.buffer(mapper.writeValueAsBytes(req)),
                            BodyCodec.string())
                    .map(
                            Unchecked.function(
                                    resp -> {
                                        int statusCode = resp.statusCode();
                                        if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                            String body = resp.body();
                                            return mapper.readValue(
                                                            body,
                                                            JmcSerializableRecordingDescriptor
                                                                    .class)
                                                    .toJmcForm();
                                        } else if (statusCode == 403) {
                                            logger.errorv(
                                                    "startRecording for {0} failed: HTTP 403",
                                                    getUri());
                                            throw new ForbiddenException(
                                                    new UnsupportedOperationException(
                                                            "startRecording"));
                                        } else {
                                            logger.errorv(
                                                    "startRecording for {0} failed: HTTP {1}",
                                                    getUri(), statusCode);
                                            throw new AgentApiException(statusCode);
                                        }
                                    }));
        } catch (JsonProcessingException e) {
            logger.error("startRecording request failed", e);
            return Uni.createFrom().failure(e);
        }
    }

    Uni<IRecordingDescriptor> startSnapshot() {
        try {
            return invoke(
                            HttpMethod.POST,
                            "/recordings/",
                            Buffer.buffer(
                                    mapper.writeValueAsBytes(
                                            new StartRecordingRequest(
                                                    "snapshot", "", "", 0, 0, 0))),
                            BodyCodec.string())
                    .map(
                            Unchecked.function(
                                    resp -> {
                                        int statusCode = resp.statusCode();
                                        if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                            String body = resp.body();
                                            return mapper.readValue(
                                                            body,
                                                            JmcSerializableRecordingDescriptor
                                                                    .class)
                                                    .toJmcForm();
                                        } else if (statusCode == 403) {
                                            throw new ForbiddenException(
                                                    new UnsupportedOperationException(
                                                            "startSnapshot"));
                                        } else {
                                            throw new AgentApiException(statusCode);
                                        }
                                    }));
        } catch (JsonProcessingException e) {
            logger.error(e);
            return Uni.createFrom().failure(e);
        }
    }

    Uni<Void> updateRecordingOptions(long id, IConstrainedMap<String> newSettings) {
        Map<String, Object> settings = new HashMap<>(newSettings.keySet().size());
        for (String key : newSettings.keySet()) {
            Object value = newSettings.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String && StringUtils.isBlank((String) value)) {
                continue;
            }
            settings.put(key, value);
        }

        try {
            return invoke(
                            HttpMethod.PATCH,
                            String.format("/recordings/%d", id),
                            Buffer.buffer(mapper.writeValueAsBytes(settings)),
                            BodyCodec.none())
                    .map(
                            resp -> {
                                int statusCode = resp.statusCode();
                                if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                    return null;
                                } else if (statusCode == 403) {
                                    throw new ForbiddenException(
                                            new UnsupportedOperationException(
                                                    "updateRecordingOptions"));
                                } else {
                                    throw new AgentApiException(statusCode);
                                }
                            });
        } catch (JsonProcessingException e) {
            logger.error(e);
            return Uni.createFrom().failure(e);
        }
    }

    Uni<Buffer> openStream(long id) {
        return invoke(HttpMethod.GET, "/recordings/" + id, BodyCodec.buffer())
                .map(
                        resp -> {
                            int statusCode = resp.statusCode();
                            if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                return resp.body();
                            } else if (statusCode == 403) {
                                throw new ForbiddenException(
                                        new UnsupportedOperationException("openStream"));
                            } else {
                                throw new AgentApiException(statusCode);
                            }
                        });
    }

    Uni<Void> stopRecording(long id) {
        // FIXME this is a terrible hack, the interfaces here should not require only an
        // IConstrainedMap with IOptionDescriptors but allow us to pass other and more simply
        // serializable data to the Agent, such as this recording state entry
        IConstrainedMap<String> map =
                new IConstrainedMap<String>() {
                    @Override
                    public Set<String> keySet() {
                        return Set.of("state");
                    }

                    @Override
                    public Object get(String key) {
                        return RecordingState.STOPPED.name();
                    }

                    @Override
                    public IConstraint<?> getConstraint(String key) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public String getPersistableString(String key) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public IMutableConstrainedMap<String> emptyWithSameConstraints() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public IMutableConstrainedMap<String> mutableCopy() {
                        throw new UnsupportedOperationException();
                    }
                };
        return updateRecordingOptions(id, map);
    }

    Uni<Void> deleteRecording(long id) {
        return invoke(
                        HttpMethod.DELETE,
                        String.format("/recordings/%d", id),
                        Buffer.buffer(),
                        BodyCodec.none())
                .map(
                        resp -> {
                            int statusCode = resp.statusCode();
                            if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)
                                    || statusCode == 404) {
                                // if the request succeeded we're OK. We're also OK if the recording
                                // could not be found - it was probably already deleted, so this is
                                // not a failure
                                return null;
                            } else if (statusCode == 403) {
                                throw new ForbiddenException(
                                        new UnsupportedOperationException("deleteRecording"));
                            } else {
                                throw new AgentApiException(statusCode);
                            }
                        });
    }

    Uni<List<IRecordingDescriptor>> activeRecordings() {
        return invoke(HttpMethod.GET, "/recordings/", BodyCodec.string())
                .map(HttpResponse::body)
                .map(
                        s -> {
                            try {
                                return mapper.readValue(
                                        s,
                                        new TypeReference<
                                                List<JmcSerializableRecordingDescriptor>>() {});
                            } catch (JsonProcessingException e) {
                                logger.error(e);
                                return List.<JmcSerializableRecordingDescriptor>of();
                            }
                        })
                .map(
                        arr ->
                                arr.stream()
                                        .map(JmcSerializableRecordingDescriptor::toJmcForm)
                                        .toList());
    }

    Uni<Collection<? extends IEventTypeInfo>> eventTypes() {
        return invoke(HttpMethod.GET, "/event-types/", BodyCodec.jsonArray())
                .map(HttpResponse::body)
                .map(arr -> arr.stream().map(o -> new AgentEventTypeInfo((JsonObject) o)).toList());
    }

    Uni<IConstrainedMap<EventOptionID>> eventSettings() {
        return invoke(HttpMethod.GET, "/event-settings/", BodyCodec.jsonArray())
                .map(HttpResponse::body)
                .map(
                        arr -> {
                            return arr.stream()
                                    .map(
                                            o -> {
                                                JsonObject json = (JsonObject) o;
                                                String eventName = json.getString("name");
                                                JsonArray jsonSettings =
                                                        json.getJsonArray("settings");
                                                Map<String, String> settings = new HashMap<>();
                                                jsonSettings.forEach(
                                                        s -> {
                                                            JsonObject j = (JsonObject) s;
                                                            settings.put(
                                                                    j.getString("name"),
                                                                    j.getString("defaultValue"));
                                                        });
                                                return Pair.of(eventName, settings);
                                            })
                                    .toList();
                        })
                .map(
                        list -> {
                            SimpleConstrainedMap<EventOptionID> result =
                                    new SimpleConstrainedMap<EventOptionID>(null);
                            list.forEach(
                                    item -> {
                                        item.getRight()
                                                .forEach(
                                                        (key, val) -> {
                                                            try {
                                                                result.put(
                                                                        new EventOptionID(
                                                                                new EventTypeIDV2(
                                                                                        item
                                                                                                .getLeft()),
                                                                                key),
                                                                        null,
                                                                        val);
                                                            } catch (
                                                                    QuantityConversionException
                                                                            qce) {
                                                                logger.warn(
                                                                        "Event settings exception",
                                                                        qce);
                                                            }
                                                        });
                                    });
                            return result;
                        });
    }

    Uni<List<String>> eventTemplates() {
        return invoke(HttpMethod.GET, "/event-templates/", BodyCodec.jsonArray())
                .map(HttpResponse::body)
                .map(arr -> arr.stream().map(Object::toString).toList());
    }

    private <T> Uni<HttpResponse<T>> invoke(HttpMethod mtd, String path, BodyCodec<T> codec) {
        return invoke(mtd, path, null, codec);
    }

    private <T> Uni<HttpResponse<T>> invoke(
            HttpMethod mtd, String path, Buffer payload, BodyCodec<T> codec) {
        logger.debugv("{0} {1} {2}", mtd, getUri(), path);

        if (tlsRequired && !getUri().getScheme().equals("https")) {
            throw new IllegalArgumentException(
                    String.format(
                            "Agent is configured with TLS required (%s) but the agent URI is not an"
                                    + " https connection.",
                            ConfigProperties.AGENT_TLS_REQUIRED));
        }

        Credential credential =
                DiscoveryPlugin.<DiscoveryPlugin>find("callback", getUri())
                        .singleResult()
                        .credential;

        HttpRequest<T> req =
                webClient
                        .request(mtd, getUri().getPort(), getUri().getHost(), path)
                        .ssl("https".equals(getUri().getScheme()))
                        .timeout(httpTimeout.toMillis())
                        .followRedirects(true)
                        .as(codec)
                        .authentication(
                                new UsernamePasswordCredentials(
                                        credential.username, credential.password));

        Uni<HttpResponse<T>> uni;
        if (payload != null) {
            uni = req.sendBuffer(payload);
        } else {
            uni = req.send();
        }
        return uni;
    }

    @ApplicationScoped
    public static class Factory {

        @Inject ObjectMapper mapper;

        @Inject
        @Named(TargetsModule.AGENT_CLIENT)
        WebClient webClient;

        @Inject Logger logger;

        @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
        Duration timeout;

        public AgentClient create(Target target) {
            return new AgentClient(target, webClient, mapper, timeout);
        }
    }

    private static class AgentEventTypeInfo implements IEventTypeInfo {

        final JsonObject json;

        AgentEventTypeInfo(JsonObject json) {
            this.json = json;
        }

        @Override
        public String getDescription() {
            return json.getString("description");
        }

        @Override
        public IEventTypeID getEventTypeID() {
            return new EventTypeIDV2(json.getString("name"));
        }

        @Override
        public String[] getHierarchicalCategory() {
            return ((List<String>)
                            json.getJsonArray("categories").getList().stream()
                                    .map(Object::toString)
                                    .toList())
                    .toArray(new String[0]);
        }

        @Override
        public String getName() {
            return json.getString("name");
        }

        static <T, V> V capture(T t) {
            // TODO clean up this generics hack
            return (V) t;
        }

        @Override
        public Map<String, ? extends IOptionDescriptor<?>> getOptionDescriptors() {
            Map<String, ? extends IOptionDescriptor<?>> result = new HashMap<>();
            JsonArray settings = json.getJsonArray("settings");
            settings.forEach(
                    setting -> {
                        String name = ((JsonObject) setting).getString("name");
                        String defaultValue = ((JsonObject) setting).getString("defaultValue");
                        result.put(
                                name,
                                capture(
                                        new IOptionDescriptor<String>() {
                                            @Override
                                            public String getName() {
                                                return name;
                                            }

                                            @Override
                                            public String getDescription() {
                                                return "";
                                            }

                                            @Override
                                            public IConstraint<String> getConstraint() {
                                                return new IConstraint<String>() {

                                                    @Override
                                                    public IConstraint<String> combine(
                                                            IConstraint<?> other) {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method 'combine'");
                                                    }

                                                    @Override
                                                    public boolean validate(String value)
                                                            throws QuantityConversionException {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method 'validate'");
                                                    }

                                                    @Override
                                                    public String persistableString(String value)
                                                            throws QuantityConversionException {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method"
                                                                        + " 'persistableString'");
                                                    }

                                                    @Override
                                                    public String parsePersisted(
                                                            String persistedValue)
                                                            throws QuantityConversionException {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method"
                                                                        + " 'parsePersisted'");
                                                    }

                                                    @Override
                                                    public String interactiveFormat(String value)
                                                            throws QuantityConversionException {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method"
                                                                        + " 'interactiveFormat'");
                                                    }

                                                    @Override
                                                    public String parseInteractive(
                                                            String interactiveValue)
                                                            throws QuantityConversionException {
                                                        // TODO Auto-generated method stub
                                                        throw new UnsupportedOperationException(
                                                                "Unimplemented method"
                                                                        + " 'parseInteractive'");
                                                    }
                                                };
                                            }

                                            @Override
                                            public String getDefault() {
                                                return defaultValue;
                                            }
                                        }));
                    });
            return result;
        }

        @Override
        public IOptionDescriptor<?> getOptionInfo(String s) {
            return getOptionDescriptors().get(s);
        }
    }

    static record MBeanInvocationRequest(
            String beanName, String operation, Object[] parameters, String[] signature) {
        MBeanInvocationRequest {
            Objects.requireNonNull(beanName);
            Objects.requireNonNull(operation);
        }
    }
}
