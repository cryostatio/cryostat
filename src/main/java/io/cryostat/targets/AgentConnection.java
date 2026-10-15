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

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.ReflectionException;
import javax.management.remote.JMXServiceURL;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.ITypedQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.common.ConnectionException;
import org.openjdk.jmc.rjmx.common.IConnectionHandle;
import org.openjdk.jmc.rjmx.common.ServiceNotAvailableException;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.CryostatFlightRecorderService;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.templates.RemoteTemplateService;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.events.S3TemplateService;
import io.cryostat.libcryostat.JvmIdentifier;
import io.cryostat.libcryostat.net.CryostatAgentMXBean;
import io.cryostat.libcryostat.net.IDException;
import io.cryostat.libcryostat.net.MBeanMetrics;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.libcryostat.triggers.SmartTrigger;
import io.cryostat.targets.AgentClient.AsyncProfile;
import io.cryostat.targets.AgentClient.AsyncProfilerStatus;
import io.cryostat.targets.AgentClient.StartProfileRequest;
import io.cryostat.targets.AgentJFRService.StartRecordingRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link io.cryostat.core.net.JFRConnection} implementation representing a Cryostat Agent instance.
 * The original JFRConnection implementation is {@link io.cryostat.core.net.JFRJMXConnection}, which
 * represents a view of a target JVM using JMX to perform remote operations. This class implements
 * the same set of operations, but based on HTTP communications with Cryostat Agent instances.
 *
 * @see io.cryostat.targets.AgentClient
 */
public class AgentConnection implements JFRConnection {

    private final AgentClient client;
    private final TemplateService customTemplateService;

    AgentConnection(AgentClient client, TemplateService customTemplateService) {
        this.client = client;
        this.customTemplateService = customTemplateService;
    }

    @Override
    public void close() throws Exception {}

    @Override
    public void connect() throws ConnectionException {
        if (!client.ping().await().atMost(client.getTimeout())) {
            throw new ConnectionException("Connection failed");
        }
    }

    @Override
    public void disconnect() {}

    public URI getUri() {
        return client.getUri();
    }

    public AsyncProfilerStatus asyncProfilerStatus() {
        return client.asyncProfilerStatus().await().atMost(client.getTimeout());
    }

    public String dumpAsyncProfile(List<String> events, Duration duration) {
        return client.dumpAsyncProfile(new StartProfileRequest(events, duration))
                .await()
                .atMost(client.getTimeout());
    }

    public List<AsyncProfile> listAsyncProfiles() {
        return client.listAsyncProfiles().await().atMost(client.getTimeout());
    }

    public boolean deleteAsyncProfile(String id) {
        return client.deleteAsyncProfile(id).await().atMost(client.getTimeout());
    }

    public InputStream streamAsyncProfile(String id) {
        return client.streamAsyncProfile(id).await().atMost(client.getTimeout());
    }

    /**
     * Starts a recording driven by async-profiler's {@code jfrsync} mode using an already-resolved
     * PRESET/CUSTOM template XML string, mirroring {@link AgentJFRService#start(IConstrainedMap,
     * String)} but additionally threading through the requested async-profiler event list.
     */
    public IRecordingDescriptor startJfrSyncRecording(
            IConstrainedMap<String> recordingOptions, String template, List<String> asprofEvents)
            throws QuantityConversionException {
        StartRecordingRequest req =
                new StartRecordingRequest(
                        recordingOptions.get("name").toString(),
                        null,
                        template,
                        extractMillis(recordingOptions, RecordingOptionsBuilder.KEY_DURATION),
                        extractBytes(recordingOptions, RecordingOptionsBuilder.KEY_MAX_SIZE),
                        extractMillis(recordingOptions, RecordingOptionsBuilder.KEY_MAX_AGE),
                        asprofEvents);
        return client.startRecording(req).await().atMost(client.getTimeout());
    }

    /**
     * Starts a recording driven by async-profiler's {@code jfrsync} mode using a {@code TARGET}
     * template (already resident on the target, referenced by name), mirroring {@link
     * AgentJFRService#start(IConstrainedMap, Template)}. Only {@code TemplateType.TARGET} is
     * supported here - PRESET/CUSTOM templates must be resolved to XML by the caller and passed via
     * {@link #startJfrSyncRecording(IConstrainedMap, String, List)} instead.
     */
    public IRecordingDescriptor startJfrSyncRecording(
            IConstrainedMap<String> recordingOptions, Template template, List<String> asprofEvents)
            throws QuantityConversionException {
        if (!TemplateType.TARGET.equals(template.getType())) {
            throw new IllegalArgumentException(
                    "Expected a TARGET template, got: " + template.getType());
        }
        StartRecordingRequest req =
                new StartRecordingRequest(
                        recordingOptions.get("name").toString(),
                        template.getName(),
                        null,
                        extractMillis(recordingOptions, RecordingOptionsBuilder.KEY_DURATION),
                        extractBytes(recordingOptions, RecordingOptionsBuilder.KEY_MAX_SIZE),
                        extractMillis(recordingOptions, RecordingOptionsBuilder.KEY_MAX_AGE),
                        asprofEvents);
        return client.startRecording(req).await().atMost(client.getTimeout());
    }

    private static long extractMillis(IConstrainedMap<String> recordingOptions, String key)
            throws QuantityConversionException {
        return Optional.ofNullable((ITypedQuantity) recordingOptions.get(key))
                .orElse(UnitLookup.MILLISECOND.quantity(0))
                .longValueIn(UnitLookup.MILLISECOND);
    }

    private static long extractBytes(IConstrainedMap<String> recordingOptions, String key)
            throws QuantityConversionException {
        return Optional.ofNullable((ITypedQuantity) recordingOptions.get(key))
                .orElse(UnitLookup.BYTE.quantity(0))
                .longValueIn(UnitLookup.BYTE);
    }

    public AgentClient.UnifiedLogStatus unifiedLogStatus() {
        return client.unifiedLogStatus().await().atMost(client.getTimeout());
    }

    public Optional<InputStream> pullUnifiedLog() {
        return client.pullUnifiedLog().await().atMost(client.getTimeout());
    }

    @Override
    public long getApproximateServerTime(Clock clock) {
        return clock.now().toEpochMilli();
    }

    @Override
    public IConnectionHandle getHandle() throws ConnectionException, IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getHost() {
        return getUri().getHost();
    }

    public static boolean isAgentConnection(URI uri) {
        return Set.of("http", "https", "cryostat-agent").contains(uri.getScheme());
    }

    @Override
    public JMXServiceURL getJMXURL() throws IOException {
        if (!isAgentConnection(getUri())) {
            throw new UnsupportedOperationException();
        }
        return new JMXServiceURL(getUri().toString());
    }

    @Override
    public JvmIdentifier getJvmIdentifier() throws IDException, IOException {
        try {
            return JvmIdentifier.from(
                    invokeMBeanOperation(
                            CryostatAgentMXBean.OBJECT_NAME, "getId", null, null, String.class),
                    getMBeanMetrics().getRuntime());
        } catch (IntrospectionException | InstanceNotFoundException | ReflectionException e) {
            throw new IDException(e);
        }
    }

    @Override
    public <T> T invokeMBeanOperation(
            String beanName,
            String operation,
            Object[] parameters,
            String[] signature,
            Class<T> returnType) {
        return client.invokeMBeanOperation(beanName, operation, parameters, signature, returnType)
                .await()
                .atMost(client.getTimeout());
    }

    @Override
    public List<SmartTrigger> listSmartTriggers() {
        return client.listTriggers().await().atMost(client.getTimeout());
    }

    @Override
    public List<String> enableSmartTriggers(String definitions) {
        return client.addSmartTriggers(definitions).await().atMost(client.getTimeout());
    }

    @Override
    public void disableSmartTrigger(String uuid) {
        client.removeSmartTrigger(uuid).await().atMost(client.getTimeout());
    }

    @Override
    public int getPort() {
        return getUri().getPort();
    }

    @Override
    public CryostatFlightRecorderService getService()
            throws ConnectionException, IOException, ServiceNotAvailableException {
        return new AgentJFRService(client, customTemplateService);
    }

    @Override
    public TemplateService getTemplateService() {
        return new RemoteTemplateService(this);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public MBeanMetrics getMBeanMetrics()
            throws ConnectionException,
                    IOException,
                    InstanceNotFoundException,
                    IntrospectionException,
                    ReflectionException {
        return client.mbeanMetrics().await().atMost(client.getTimeout());
    }

    @ApplicationScoped
    public static class Factory {

        @ConfigProperty(name = ConfigProperties.AGENT_TLS_REQUIRED)
        boolean tlsRequired;

        @Inject AgentClient.Factory clientFactory;
        @Inject S3TemplateService customTemplateService;
        @Inject Logger logger;

        public AgentConnection createConnection(Target target) throws MalformedURLException {
            if (tlsRequired && !target.connectUrl.getScheme().equals("https")) {
                throw new MalformedURLException(
                        String.format(
                                "Agent connections are required to be TLS by (%s)",
                                ConfigProperties.AGENT_TLS_REQUIRED));
            }
            return new AgentConnection(clientFactory.create(target), customTemplateService);
        }
    }
}
