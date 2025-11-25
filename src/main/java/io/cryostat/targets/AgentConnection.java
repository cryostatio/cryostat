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
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.ReflectionException;
import javax.management.remote.JMXServiceURL;

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
import io.cryostat.libcryostat.net.IDException;
import io.cryostat.libcryostat.net.MBeanMetrics;
import io.cryostat.libcryostat.sys.Clock;

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
class AgentConnection implements JFRConnection {

    private final AgentClient client;
    private final TemplateService customTemplateService;
    private final Logger logger = Logger.getLogger(getClass());

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
                            "io.cryostat.agent.CryostatAgent:name=agent",
                            "getId",
                            null,
                            null,
                            String.class),
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
