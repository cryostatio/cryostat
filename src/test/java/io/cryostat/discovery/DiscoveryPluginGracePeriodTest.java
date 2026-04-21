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
package io.cryostat.discovery;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.ConfigProperties;
import io.cryostat.discovery.DiscoveryPlugin.PluginCallback;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

@QuarkusTest
public class DiscoveryPluginGracePeriodTest extends AbstractTransactionalTestBase {

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_MAX_FAILURES)
    int maxConsecutiveFailures;

    @Inject Discovery.RefreshPluginJob refreshPluginJob;

    @InjectMock PluginCallbackFactory callbackFactory;

    private static final String PLUGIN_ID_MAP_KEY = "pluginId";
    private static final String REFRESH_MAP_KEY = "refresh";

    private PluginCallback mockCallback;

    @BeforeEach
    public void setupMocks() throws Exception {
        mockCallback = mock(PluginCallback.class);
        Mockito.reset(callbackFactory);
        // By default, return mockCallback for any plugin
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);
    }

    @Test
    @Transactional
    public void testConsecutiveFailuresIncrement() throws Exception {
        // Create credentials first
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl == 'http://localhost:9999/nonexistent'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // Create a plugin manually for testing
        var realm = new DiscoveryNode();
        realm.name = "test_failure_realm";
        realm.nodeType = NodeType.BaseNodeType.REALM.getKind();
        realm.persist();

        var plugin = new DiscoveryPlugin();
        plugin.realm = realm;
        plugin.callback =
                URI.create(
                        String.format(
                                "http://storedcredentials:%d@localhost:9999/nonexistent",
                                credentialId));
        plugin.builtin = false;
        plugin.consecutiveFailures = 0;
        plugin.persist();

        UUID pluginId = plugin.id;

        assertNotNull(plugin);
        assertEquals(0, plugin.consecutiveFailures);
        assertNull(plugin.lastSuccessfulPing);

        // Mock the callback to throw an exception simulating network failure
        doThrow(new ProcessingException("Connection refused")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        // Simulate ping failures
        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // First failure
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin1 = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertEquals(1, updatedPlugin1.consecutiveFailures);
        assertNull(updatedPlugin1.lastSuccessfulPing);

        // Second failure
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin2 = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertEquals(2, updatedPlugin2.consecutiveFailures);
        assertNull(updatedPlugin2.lastSuccessfulPing);
    }

    @Test
    @Transactional
    public void testPluginDeletedAfterMaxFailures() throws Exception {
        // Create credentials first
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl == 'http://localhost:9999/nonexistent'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // Create a plugin manually for testing
        var realm = new DiscoveryNode();
        realm.name = "test_max_failures_realm";
        realm.nodeType = NodeType.BaseNodeType.REALM.getKind();
        realm.persist();

        var plugin = new DiscoveryPlugin();
        plugin.realm = realm;
        plugin.callback =
                URI.create(
                        String.format(
                                "http://storedcredentials:%d@localhost:9999/nonexistent",
                                credentialId));
        plugin.builtin = false;
        plugin.consecutiveFailures = maxConsecutiveFailures - 1;
        plugin.persist();

        UUID pluginId = plugin.id;

        // Mock the callback to throw an exception simulating network failure
        doThrow(new ProcessingException("Connection refused")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // This failure should trigger deletion
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var deletedPlugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertNull(deletedPlugin, "Plugin should be deleted after max consecutive failures");
    }

    @Test
    public void testConsecutiveFailuresResetOnSuccess() throws Exception {
        // For this test, use real callback creation since we're testing against real endpoint
        doCallRealMethod().when(callbackFactory).create(any(DiscoveryPlugin.class));

        // Create a plugin with a valid callback
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl =="
                                                + " 'http://localhost:8081/health/liveness'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        var callback =
                String.format(
                        "http://storedcredentials:%d@localhost:8081/health/liveness", credentialId);

        var registration =
                given().log()
                        .all()
                        .when()
                        .body(Map.of("realm", "test_reset_realm", "callback", callback))
                        .contentType(ContentType.JSON)
                        .post("/api/v4/discovery")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();

        var pluginId = UUID.fromString(registration.getString("id"));

        // Manually set some consecutive failures
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            var plugin =
                                    DiscoveryPlugin.<DiscoveryPlugin>find("id", pluginId)
                                            .firstResult();
                            plugin.consecutiveFailures = 2;
                            plugin.persist();
                        });

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // Execute successful ping
        refreshPluginJob.execute(context);

        DiscoveryPlugin updatedPlugin =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        DiscoveryPlugin.<DiscoveryPlugin>find("id", pluginId)
                                                .firstResult());

        assertEquals(0, updatedPlugin.consecutiveFailures, "Consecutive failures should be reset");
        assertNotNull(updatedPlugin.lastSuccessfulPing, "Last successful ping should be set");
        assertTrue(
                updatedPlugin.lastSuccessfulPing.isBefore(Instant.now().plusSeconds(1)),
                "Last successful ping should be recent");
    }

    @Test
    @Transactional
    public void testPluginNotDeletedBeforeMaxFailures() throws Exception {
        // Create credentials first
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl == 'http://localhost:9999/nonexistent'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // Create a plugin manually for testing
        var realm = new DiscoveryNode();
        realm.name = "test_not_deleted_realm";
        realm.nodeType = NodeType.BaseNodeType.REALM.getKind();
        realm.persist();

        var plugin = new DiscoveryPlugin();
        plugin.realm = realm;
        plugin.callback =
                URI.create(
                        String.format(
                                "http://storedcredentials:%d@localhost:9999/nonexistent",
                                credentialId));
        plugin.builtin = false;
        plugin.consecutiveFailures = 0;
        plugin.persist();

        UUID pluginId = plugin.id;

        // Mock the callback to throw an exception simulating network failure
        doThrow(new ProcessingException("Connection refused")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // Execute failures up to max-1
        for (int i = 0; i < maxConsecutiveFailures - 1; i++) {
            try {
                refreshPluginJob.execute(context);
            } catch (Exception e) {
                // Expected to fail
            }
        }

        var updatedPlugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);

        assertNotNull(
                updatedPlugin, "Plugin should not be deleted before max consecutive failures");
        assertEquals(
                maxConsecutiveFailures - 1,
                updatedPlugin.consecutiveFailures,
                "Consecutive failures should be tracked");
    }
}
