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
package itest.agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

@QuarkusIntegrationTest
@EnabledIf("enabled")
public class AgentDiscoveryIT extends AgentTestBase {
    @Test
    void shouldDiscoverTarget() throws InterruptedException, TimeoutException, ExecutionException {
        Assertions.assertDoesNotThrow(() -> waitForDiscovery());
    }
}
