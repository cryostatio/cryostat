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
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);
    }

    private UUID createPluginInCommittedTransaction(
            long credentialId, String realmName, int consecutiveFailures) {
        return QuarkusTransaction.requiringNew()
                .call(
                        () -> {
                            var realm = new DiscoveryNode();
                            realm.name = realmName;
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
                            plugin.consecutiveFailures = consecutiveFailures;
                            plugin.persist();

                            return plugin.id;
                        });
    }

    @Test
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

        // Create a plugin in a committed transaction so the job can see it
        UUID pluginId = createPluginInCommittedTransaction(credentialId, "test_failure_realm", 0);

        var plugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertNotNull(plugin);
        assertEquals(0, plugin.consecutiveFailures);
        assertNull(plugin.lastSuccessfulPing);

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

        var updatedPlugin1 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId));
        assertEquals(1, updatedPlugin1.consecutiveFailures);
        assertNull(updatedPlugin1.lastSuccessfulPing);

        // Clear backoff to allow second ping attempt
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            var p = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
                            if (p != null) {
                                p.nextPingAt = null;
                                p.persist();
                            }
                        });

        // Second failure
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin2 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId));
        assertEquals(2, updatedPlugin2.consecutiveFailures);
        assertNull(updatedPlugin2.lastSuccessfulPing);
    }

    @Test
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

        // Create a plugin in a committed transaction
        UUID pluginId =
                createPluginInCommittedTransaction(
                        credentialId, "test_max_failures_realm", maxConsecutiveFailures - 1);

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

        // Create a plugin in a committed transaction
        UUID pluginId =
                createPluginInCommittedTransaction(credentialId, "test_not_deleted_realm", 0);

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

            // Clear backoff to allow next ping attempt
            if (i < maxConsecutiveFailures - 2) {
                QuarkusTransaction.requiringNew()
                        .run(
                                () -> {
                                    var p = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
                                    if (p != null) {
                                        p.nextPingAt = null;
                                        p.persist();
                                    }
                                });
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

    @Test
    public void testLastFailedPingTracked() throws Exception {
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

        // Create a plugin in a committed transaction
        UUID pluginId =
                createPluginInCommittedTransaction(credentialId, "test_failure_tracking_realm", 0);

        // Mock the callback to throw an exception simulating network failure
        doThrow(new ProcessingException("Connection refused")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // Execute failure
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertNotNull(updatedPlugin.lastFailedPing, "Last failed ping should be set");
        assertTrue(
                updatedPlugin.lastFailedPing.isBefore(Instant.now().plusSeconds(1)),
                "Last failed ping should be recent");
    }

    @Test
    public void testBackoffMultiplierIncreasesOnFailure() throws Exception {
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

        // Create a plugin in a committed transaction
        UUID pluginId = createPluginInCommittedTransaction(credentialId, "test_backoff_realm", 0);

        var plugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertEquals(1, plugin.backoffMultiplier);
        assertNull(plugin.nextPingAt);

        // Mock the callback to throw an exception simulating network failure
        doThrow(new ProcessingException("Connection refused")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // First failure - backoff should double to 2
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin1 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId));
        assertEquals(2, updatedPlugin1.backoffMultiplier, "Backoff multiplier should double");
        assertNotNull(updatedPlugin1.nextPingAt, "Next ping time should be set");

        // Clear backoff to allow second ping attempt
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            var p = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
                            if (p != null) {
                                p.nextPingAt = null;
                                p.persist();
                            }
                        });

        // Second failure - backoff should double to 4
        try {
            refreshPluginJob.execute(context);
        } catch (Exception e) {
            // Expected to fail
        }

        var updatedPlugin2 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId));
        assertEquals(4, updatedPlugin2.backoffMultiplier, "Backoff multiplier should double again");
    }

    @Test
    public void testBackoffSkipsPingWhenNotReady() throws Exception {
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

        // Create a plugin in a committed transaction with backoff state
        UUID pluginId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    var realm = new DiscoveryNode();
                                    realm.name = "test_backoff_skip_realm";
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
                                    plugin.consecutiveFailures = 1;
                                    plugin.backoffMultiplier = 2;
                                    plugin.nextPingAt =
                                            Instant.now().plusSeconds(3600); // 1 hour in future
                                    plugin.persist();

                                    return plugin.id;
                                });

        // Mock the callback - it should NOT be called due to backoff
        doThrow(new ProcessingException("Should not be called")).when(mockCallback).ping();
        when(callbackFactory.create(any(DiscoveryPlugin.class))).thenReturn(mockCallback);

        var context = mock(JobExecutionContext.class);
        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, pluginId);
        dataMap.put(REFRESH_MAP_KEY, false);
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        // Execute - should skip ping due to backoff
        refreshPluginJob.execute(context);

        // Verify ping was never called
        verify(mockCallback, never()).ping();

        var updatedPlugin = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);
        assertEquals(
                1,
                updatedPlugin.consecutiveFailures,
                "Consecutive failures should not change when ping is skipped");
    }

    @Test
    public void testBackoffResetsOnSuccess() throws Exception {
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
                        .body(Map.of("realm", "test_backoff_reset_realm", "callback", callback))
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

        // Manually set backoff state
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            var plugin =
                                    DiscoveryPlugin.<DiscoveryPlugin>find("id", pluginId)
                                            .firstResult();
                            plugin.consecutiveFailures = 2;
                            plugin.backoffMultiplier = 4;
                            plugin.nextPingAt = Instant.now().minusSeconds(1); // In the past
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
        assertEquals(1, updatedPlugin.backoffMultiplier, "Backoff multiplier should be reset");
        assertNull(updatedPlugin.nextPingAt, "Next ping time should be cleared");
        assertNotNull(updatedPlugin.lastSuccessfulPing, "Last successful ping should be set");
    }
}
