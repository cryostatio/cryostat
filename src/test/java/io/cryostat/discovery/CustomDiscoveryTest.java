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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class CustomDiscoveryTest {

    @Inject CustomDiscovery customDiscovery;

    @InjectMock TargetConnectionManager connectionManager;

    @Test
    @Transactional
    public void testCreateTargetWithValidJmxUrl() throws Exception {
        // Arrange
        Target target = new Target();
        target.connectUrl = new URI("service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi");
        target.alias = "test-alias";

        // Mock connection manager to return a sample JVM ID
        when(connectionManager.executeDirect(any(), any(), any()))
                .thenReturn(Uni.createFrom().item("some-jvm-id"));

        // Act
        Response response = customDiscovery.create(target, false, false);

        // Assert
        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        Target createdTarget = Target.find("connectUrl", target.connectUrl).singleResult();
        assertNotNull(createdTarget);
        assertEquals("some-jvm-id", createdTarget.jvmId);
    }

}
