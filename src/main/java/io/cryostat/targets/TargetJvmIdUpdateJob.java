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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.libcryostat.JvmIdentifier;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public class TargetJvmIdUpdateJob implements Job {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    ExecutorService executor = ForkJoinPool.commonPool();

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionTimeout;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Target.<Target>stream("#Target.unconnected")
                .forEach(
                        t -> {
                            executor.submit(
                                    () -> {
                                        try {
                                            updateTargetJvmId(t.id);
                                        } catch (Exception e) {
                                            logger.warn(e);
                                        }
                                    });
                        });
    }

    private void updateTargetJvmId(long id) {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            try {
                                Target target = Target.getTargetById(id);
                                target.jvmId =
                                        connectionManager
                                                .executeDirect(
                                                        target,
                                                        Optional.empty(),
                                                        JFRConnection::getJvmIdentifier)
                                                .map(JvmIdentifier::getHash)
                                                .await()
                                                .atMost(connectionTimeout);
                                target.persist();
                            } catch (Exception e) {
                                logger.error(e);
                            }
                        });
    }
}
