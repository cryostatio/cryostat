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

import java.util.concurrent.CompletionException;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.ext.web.handler.HttpException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TargetConnectionManagerAgentFailureStatusTest {

    @Inject TargetConnectionManager tcm;

    @Test
    void testClientErrorsArePassedThrough() {
        for (int status : new int[] {400, 404, 409, 429}) {
            assertEquals(status, tcm.agentFailureStatus(new AgentApiException(status)));
        }
    }

    @Test
    void testUnauthorizedAndForbiddenAreMappedToBadGateway() {
        assertEquals(502, tcm.agentFailureStatus(new AgentApiException(401)));
        assertEquals(502, tcm.agentFailureStatus(new AgentApiException(403)));
        assertEquals(
                502,
                tcm.agentFailureStatus(
                        new AgentApiException(403, new UnsupportedOperationException("op"))));
    }

    @Test
    void testServerErrorsAreMappedToBadGateway() {
        for (int status : new int[] {500, 501, 502, 503, 504}) {
            assertEquals(502, tcm.agentFailureStatus(new AgentApiException(status)));
        }
    }

    @Test
    void testNonErrorStatusesAreMappedToBadGateway() {
        for (int status : new int[] {200, 302}) {
            assertEquals(502, tcm.agentFailureStatus(new AgentApiException(status)));
        }
    }

    @Test
    void testStatusIsFoundInWrappedCauseChain() {
        Throwable wrapped =
                new CompletionException(
                        new HttpException(500, new RuntimeException(new AgentApiException(404))));
        assertEquals(404, tcm.agentFailureStatus(wrapped));
    }

    @Test
    void testNoAgentFailureInChainIsMappedToBadGateway() {
        assertEquals(502, tcm.agentFailureStatus(new RuntimeException("unrelated")));
    }
}
