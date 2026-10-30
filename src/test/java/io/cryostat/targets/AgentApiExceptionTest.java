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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgentApiExceptionTest {

    @Test
    void testConstructorMaps401To502() {
        AgentApiException exc = new AgentApiException(401);
        assertEquals(502, exc.getResponse().getStatus());
        assertEquals("Unexpected HTTP response code 401", exc.getMessage());
    }

    @Test
    void testConstructorWithCauseMaps401To502() {
        Throwable cause = new RuntimeException("test cause");
        AgentApiException exc = new AgentApiException(401, cause);
        assertEquals(502, exc.getResponse().getStatus());
        assertEquals("Unexpected HTTP response code 401", exc.getMessage());
        assertSame(cause, exc.getCause());
    }

    @Test
    void testConstructorPreserves403() {
        AgentApiException exc = new AgentApiException(403);
        assertEquals(403, exc.getResponse().getStatus());
        assertEquals("Unexpected HTTP response code 403", exc.getMessage());
    }

    @Test
    void testConstructorWithCausePreserves403() {
        Throwable cause = new RuntimeException("test cause");
        AgentApiException exc = new AgentApiException(403, cause);
        assertEquals(403, exc.getResponse().getStatus());
        assertEquals("Unexpected HTTP response code 403", exc.getMessage());
        assertSame(cause, exc.getCause());
    }

    @Test
    void testConstructorPreservesOtherStatusCodes() {
        for (int status : new int[] {200, 400, 404, 500, 503}) {
            AgentApiException exc = new AgentApiException(status);
            assertEquals(status, exc.getResponse().getStatus());
            assertEquals(
                    String.format("Unexpected HTTP response code %d", status), exc.getMessage());
        }
    }

    @Test
    void testConstructorWithCausePreservesOtherStatusCodes() {
        Throwable cause = new RuntimeException("test cause");
        for (int status : new int[] {200, 400, 404, 500, 503}) {
            AgentApiException exc = new AgentApiException(status, cause);
            assertEquals(status, exc.getResponse().getStatus());
            assertEquals(
                    String.format("Unexpected HTTP response code %d", status), exc.getMessage());
            assertSame(cause, exc.getCause());
        }
    }
}
