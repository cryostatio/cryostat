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
package io.cryostat;

import java.util.List;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.quartz.SchedulerException;

public abstract class AbstractTransactionalTestBase extends AbstractTestBase {

    @Inject Flyway flyway;
    @Inject EntityManager entityManager;

    @BeforeEach
    void migrateFlyway() throws SchedulerException {
        shutdownScheduler();
        QuarkusTransaction.requiringNew().run(this::cleanAuditRecords);
        flyway.clean();
        flyway.migrate();
        flyway.validate();
        entityManager.clear();
        restartScheduler();
    }

    void cleanAuditRecords() {
        List<String> tables =
                List.of(
                        "Target",
                        "Rule",
                        "ActiveRecording",
                        "MatchExpression",
                        "DiscoveryPlugin",
                        "DiscoveryNode",
                        "Credential",
                        "GarbageCollection",
                        "ThreadDump",
                        "HeapDump",
                        "EventTemplate",
                        "ArchivedRecording",
                        "ProbeTemplate",
                        "AsyncProfilerRecording");
        for (String table : tables) {
            entityManager
                    .createNativeQuery(String.format("DELETE FROM %s_AUD", table))
                    .executeUpdate();
        }
        entityManager.createNativeQuery("DELETE FROM REVINFO").executeUpdate();
    }
}
