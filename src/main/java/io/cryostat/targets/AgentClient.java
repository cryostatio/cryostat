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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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
import org.openjdk.jmc.rjmx.common.ConnectionException;

import io.cryostat.ConfigProperties;
import io.cryostat.core.serialization.JmcSerializableRecordingDescriptor;
import io.cryostat.discovery.DiscoveryPlugin;
import io.cryostat.discovery.DiscoveryPlugin.PluginCallback.DiscoveryPluginAuthorizationHeaderFactory;
import io.cryostat.libcryostat.net.MBeanMetrics;
import io.cryostat.targets.AgentJFRService.StartRecordingRequest;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jdk.jfr.RecordingState;
import org.apache.commons.io.input.ProxyInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.client.handlers.RedirectHandler;

/**
 * Client layer for HTTP(S) communications with Cryostat Agent instances. See
 * https://github.com/cryostatio/cryostat-agent . This is an HTTP-based client for communicating
 * with Cryostat Agent instances.
 *
 * @see io.cryostat.target.AgentJFRService
 * @see io.cryostat.target.AgentConnection
 */
public class AgentClient {

    public static final String NULL_CREDENTIALS = "No credentials found for agent";

    private final Target target;
    private final AgentRestClient agentRestClient;
    private final Duration httpTimeout;
    private final ObjectMapper mapper;
    private final Logger logger = Logger.getLogger(getClass());

    private AgentClient(
            Target target,
            AgentRestClient agentRestClient,
            ObjectMapper mapper,
            Duration httpTimeout) {
        this.target = target;
        this.agentRestClient = agentRestClient;
        this.mapper = mapper;
        this.httpTimeout = httpTimeout;
    }

    Target getTarget() {
        return target;
    }

    URI getUri() {
        var uri = getTarget().connectUrl;
        return uri;
    }

    Duration getTimeout() {
        return httpTimeout;
    }

    Uni<Boolean> ping() {
        return agentRestClient
                .ping()
                .invoke(Response::close)
                .map(Response::getStatus)
                .map(status -> HttpStatusCodeIdentifier.isSuccessCode(status));
    }

    Uni<MBeanMetrics> mbeanMetrics() {
        return agentRestClient
                .getMbeanMetrics()
                .map(
                        r -> {
                            try (r;
                                    var is = (InputStream) r.getEntity()) {
                                return mapper.readValue(is, MBeanMetrics.class);
                            } catch (IOException e) {
                                throw new AgentApiException(
                                        Response.Status.BAD_GATEWAY.getStatusCode(), e);
                            }
                        });
    }

    <T> Uni<T> invokeMBeanOperation(
            String beanName,
            String operation,
            Object[] parameters,
            String[] signature,
            Class<T> returnType) {
        try {
            var req = new MBeanInvocationRequest(beanName, operation, parameters, signature);
            return agentRestClient
                    .invokeMBeanOperation(new ByteArrayInputStream(mapper.writeValueAsBytes(req)))
                    .map(
                            Unchecked.function(
                                    resp -> {
                                        int statusCode = resp.getStatus();
                                        if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                            return resp;
                                        } else if (statusCode == 403) {
                                            logger.errorv(
                                                    "invokeMBeanOperation {0} ({1}) for {2} failed:"
                                                            + " HTTP 403",
                                                    beanName, operation, getUri());
                                            throw new AgentApiException(
                                                    Response.Status.FORBIDDEN.getStatusCode(),
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
                    .map(
                            Unchecked.function(
                                    buff -> {
                                        if (returnType.equals(String.class)) {
                                            return mapper.readValue(
                                                    (InputStream) buff.getEntity(), returnType);
                                        }
                                        // TODO implement conditional handling based on expected
                                        // returnType
                                        return null;
                                    }));
        } catch (JsonProcessingException e) {
            logger.error("invokeMBeanOperation request failed", e);
            return Uni.createFrom().failure(e);
        }
    }

    Uni<IRecordingDescriptor> startRecording(StartRecordingRequest req) {
        return agentRestClient
                .startRecording(req)
                .map(
                        Unchecked.function(
                                resp -> {
                                    int statusCode = resp.getStatus();
                                    try (resp;
                                            var is = (InputStream) resp.getEntity()) {
                                        if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                            return mapper.readValue(
                                                            is,
                                                            JmcSerializableRecordingDescriptor
                                                                    .class)
                                                    .toJmcForm();
                                        } else if (statusCode == 403) {
                                            logger.errorv(
                                                    "startRecording for {0} failed: HTTP 403",
                                                    getUri());
                                            throw new AgentApiException(
                                                    Response.Status.FORBIDDEN.getStatusCode(),
                                                    new UnsupportedOperationException(
                                                            "startRecording"));
                                        } else {
                                            logger.errorv(
                                                    "startRecording for {0} failed: HTTP {1}",
                                                    getUri(), statusCode);
                                            throw new AgentApiException(statusCode);
                                        }
                                    }
                                }));
    }

    Uni<IRecordingDescriptor> startSnapshot() {
        return startRecording(new StartRecordingRequest("snapshot", "", "", 0, 0, 0));
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
        return agentRestClient
                .updateRecordingOptions(id, settings)
                .invoke(Response::close)
                .map(
                        resp -> {
                            int statusCode = resp.getStatus();
                            if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                return null;
                            } else if (statusCode == 403) {
                                throw new AgentApiException(
                                        Response.Status.FORBIDDEN.getStatusCode(),
                                        new UnsupportedOperationException(
                                                "updateRecordingOptions"));
                            } else {
                                throw new AgentApiException(statusCode);
                            }
                        });
    }

    Uni<InputStream> openStream(long id) {
        return agentRestClient
                .openStream(id)
                .map(
                        resp -> {
                            int statusCode = resp.getStatus();
                            if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)) {
                                return new ProxyInputStream((InputStream) resp.getEntity()) {
                                    @Override
                                    public void close() throws IOException {
                                        in.close();
                                        resp.close();
                                    }
                                };
                            } else if (statusCode == 403) {
                                throw new AgentApiException(
                                        Response.Status.FORBIDDEN.getStatusCode(),
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
                        throw new AgentApiException(
                                Response.Status.BAD_REQUEST.getStatusCode(),
                                new UnsupportedOperationException());
                    }

                    @Override
                    public String getPersistableString(String key) {
                        throw new AgentApiException(
                                Response.Status.BAD_REQUEST.getStatusCode(),
                                new UnsupportedOperationException());
                    }

                    @Override
                    public IMutableConstrainedMap<String> emptyWithSameConstraints() {
                        throw new AgentApiException(
                                Response.Status.BAD_REQUEST.getStatusCode(),
                                new UnsupportedOperationException());
                    }

                    @Override
                    public IMutableConstrainedMap<String> mutableCopy() {
                        throw new AgentApiException(
                                Response.Status.BAD_REQUEST.getStatusCode(),
                                new UnsupportedOperationException());
                    }
                };
        return updateRecordingOptions(id, map);
    }

    Uni<Void> deleteRecording(long id) {
        return agentRestClient
                .deleteRecording(id)
                .invoke(Response::close)
                .map(
                        resp -> {
                            int statusCode = resp.getStatus();
                            if (HttpStatusCodeIdentifier.isSuccessCode(statusCode)
                                    || statusCode == 404) {
                                // if the request succeeded we're OK. We're also OK if the recording
                                // could not be found - it was probably already deleted, so this is
                                // not a failure
                                return null;
                            } else if (statusCode == 403) {
                                throw new AgentApiException(
                                        Response.Status.FORBIDDEN.getStatusCode(),
                                        new UnsupportedOperationException("deleteRecording"));
                            } else {
                                throw new AgentApiException(statusCode);
                            }
                        });
    }

    Uni<List<IRecordingDescriptor>> activeRecordings() {
        return agentRestClient
                .listRecordings()
                .map(
                        Unchecked.function(
                                resp -> {
                                    try (resp;
                                            var is = (InputStream) resp.getEntity()) {
                                        return Arrays.asList(
                                                mapper.readValue(
                                                        is,
                                                        JmcSerializableRecordingDescriptor[]
                                                                .class));
                                    }
                                }))
                .map(
                        arr ->
                                arr.stream()
                                        .map(JmcSerializableRecordingDescriptor::toJmcForm)
                                        .toList());
    }

    Uni<Collection<? extends IEventTypeInfo>> eventTypes() {
        return agentRestClient
                .listEventTypes()
                .map(
                        Unchecked.function(
                                resp -> {
                                    try (resp;
                                            var is = (InputStream) resp.getEntity()) {
                                        return Arrays.asList(mapper.readValue(is, Map[].class))
                                                .stream()
                                                .map(JsonObject::new)
                                                .map(AgentEventTypeInfo::new)
                                                .toList();
                                    }
                                }));
    }

    Uni<IConstrainedMap<EventOptionID>> eventSettings() {
        return agentRestClient
                .listEventSettings()
                .map(
                        Unchecked.function(
                                resp -> {
                                    try (resp;
                                            var is = (InputStream) resp.getEntity()) {
                                        return Arrays.asList(mapper.readValue(is, Map[].class));
                                    }
                                }))
                .map(
                        arr -> {
                            return arr.stream()
                                    .map(
                                            o -> {
                                                @SuppressWarnings("unchecked")
                                                JsonObject json =
                                                        new JsonObject((Map<String, Object>) o);
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
        return agentRestClient
                .listEventTemplates()
                .map(
                        Unchecked.function(
                                resp -> {
                                    try (resp;
                                            var is = (InputStream) resp.getEntity()) {
                                        return Arrays.asList(mapper.readValue(is, String[].class));
                                    }
                                }));
    }

    @ApplicationScoped
    public static class Factory {

        @Inject ObjectMapper mapper;
        @Inject Logger logger;

        @ConfigProperty(name = ConfigProperties.AGENT_TLS_REQUIRED)
        boolean tlsEnabled;

        @ConfigProperty(name = ConfigProperties.AGENT_REST_CLIENT_FOLLOW_ALL_REDIRECTS)
        boolean followAllRedirects;

        @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
        Duration timeout;

        public AgentClient create(Target target) {
            var uri = target.connectUrl;

            if (tlsEnabled && !uri.getScheme().equals("https")) {
                throw new IllegalArgumentException(
                        String.format(
                                "Agent is configured with TLS enabled (%s) but the agent URI is not"
                                        + " an https connection.",
                                ConfigProperties.AGENT_TLS_REQUIRED));
            }

            Supplier<UsernamePasswordCredentials> credentialSupplier =
                    () ->
                            QuarkusTransaction.requiringNew()
                                    .call(
                                            () -> {
                                                var credential =
                                                        DiscoveryPlugin.<DiscoveryPlugin>find(
                                                                        "callback", uri)
                                                                .singleResult()
                                                                .credential;
                                                if (credential == null) {
                                                    throw new ConnectionException(NULL_CREDENTIALS);
                                                }
                                                return new UsernamePasswordCredentials(
                                                        credential.username, credential.password);
                                            });
            var agentRestClientBuilder =
                    QuarkusRestClientBuilder.newBuilder()
                            .baseUri(uri)
                            .clientHeadersFactory(
                                    new DiscoveryPluginAuthorizationHeaderFactory(
                                            credentialSupplier));
            if (followAllRedirects) {
                agentRestClientBuilder.register(
                        new ContextResolver<RedirectHandler>() {
                            @Override
                            public RedirectHandler getContext(Class<?> type) {
                                return response -> {
                                    if (Response.Status.Family.familyOf(response.getStatus())
                                            == Response.Status.Family.REDIRECTION) {
                                        return response.getLocation();
                                    }
                                    return null;
                                };
                            }
                        });
            }
            return new AgentClient(
                    target, agentRestClientBuilder.build(AgentRestClient.class), mapper, timeout);
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
        @SuppressWarnings("unchecked")
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

        @Override
        public Map<String, ? extends IOptionDescriptor<?>> getOptionDescriptors() {
            Map<String, IOptionDescriptor<?>> result = new HashMap<>();
            JsonArray settings = json.getJsonArray("settings");
            settings.forEach(
                    setting -> {
                        String name = ((JsonObject) setting).getString("name");
                        String defaultValue = ((JsonObject) setting).getString("defaultValue");
                        result.put(
                                name,
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
                                            public String parsePersisted(String persistedValue)
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
                                            public String parseInteractive(String interactiveValue)
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
                                });
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
