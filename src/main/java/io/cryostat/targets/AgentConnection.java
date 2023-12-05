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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.ReflectionException;
import javax.management.remote.JMXServiceURL;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.rjmx.ConnectionException;
import org.openjdk.jmc.rjmx.IConnectionHandle;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;

import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.JvmIdentifier;
import io.cryostat.core.net.CryostatFlightRecorderService;
import io.cryostat.core.net.IDException;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.MBeanMetrics;
import io.cryostat.core.net.MemoryMetrics;
import io.cryostat.core.net.OperatingSystemMetrics;
import io.cryostat.core.net.RuntimeMetrics;
import io.cryostat.core.net.ThreadMetrics;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.core.templates.TemplateType;

import io.vertx.mutiny.ext.web.client.WebClient;
import org.jsoup.nodes.Document;

class AgentConnection implements JFRConnection {

    private final URI agentUri;
    private final WebClient webClient;
    private final Clock clock;

    AgentConnection(URI agentUri, WebClient webClient, Clock clock) {
        this.agentUri = agentUri;
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public void close() throws Exception {}

    @Override
    public void connect() throws ConnectionException {
        // TODO test connection by pinging agent callback
    }

    @Override
    public void disconnect() {}

    @Override
    public long getApproximateServerTime(Clock arg0) {
        return clock.now().toEpochMilli();
    }

    @Override
    public IConnectionHandle getHandle() throws ConnectionException, IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getHost() {
        return agentUri.getHost();
    }

    @Override
    public JMXServiceURL getJMXURL() throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getJvmId() throws IDException, IOException {
        // this should have already been populated when the agent published itself to the Discovery
        // API. If not, then this will fail, but we were in a bad state to begin with.
        return Target.getTargetByConnectUrl(agentUri).jvmId;
    }

    @Override
    public JvmIdentifier getJvmIdentifier() throws IDException, IOException {
        // try {
        //     return JvmIdentifier.from(getMBeanMetrics().getRuntime());
        // } catch (IntrospectionException | InstanceNotFoundException | ReflectionException e) {
        //     throw new IDException(e);
        // }
        throw new UnsupportedOperationException("Unimplemented method 'getJvmIdentifier'");
    }

    @Override
    public int getPort() {
        return agentUri.getPort();
    }

    @Override
    public CryostatFlightRecorderService getService()
            throws ConnectionException, IOException, ServiceNotAvailableException {
        return new AgentJFRService(webClient);
    }

    @Override
    public TemplateService getTemplateService() {
        return new TemplateService() {

            @Override
            public Optional<IConstrainedMap<EventOptionID>> getEvents(
                    String name, TemplateType type) throws FlightRecorderException {
                return Optional.empty();
            }

            @Override
            public List<Template> getTemplates() throws FlightRecorderException {
                return List.of();
            }

            @Override
            public Optional<Document> getXml(String name, TemplateType type)
                    throws FlightRecorderException {
                return Optional.empty();
            }
        };
    }

    @Override
    public boolean isConnected() {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public MBeanMetrics getMBeanMetrics()
            throws ConnectionException,
                    IOException,
                    InstanceNotFoundException,
                    IntrospectionException,
                    ReflectionException {
        // TODO
        RuntimeMetrics runtime = new RuntimeMetrics(Map.of());
        MemoryMetrics memory = new MemoryMetrics(Map.of());
        ThreadMetrics thread = new ThreadMetrics(Map.of());
        OperatingSystemMetrics operatingSystem = new OperatingSystemMetrics(Map.of());
        return new MBeanMetrics(runtime, memory, thread, operatingSystem, getJvmId());
    }
}
