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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;

/**
 * Attempt to connect to a remote target JVM to retrieve {@link java.lang.management.RuntimeMXBean}
 * data and calculate the JVM hash ID.
 *
 * @see io.cryostat.target.Target
 */
@DisallowConcurrentExecution
public class TargetUpdateJob implements Job {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;
    @Inject TargetUpdateService updateService;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        List<Target> targets;
        Long targetId = (Long) context.getJobDetail().getJobDataMap().get("targetId");
        boolean unconnected =
                Optional.ofNullable(
                                (Boolean) context.getJobDetail().getJobDataMap().get("unconnected"))
                        .orElse(false);
        if (targetId != null) {
            try {
                targets = List.of(Target.getTargetById(targetId));
            } catch (PersistenceException e) {
                // target disappeared in the meantime. No big deal.
                logger.debug(e);
                return;
            }
        } else if (unconnected) {
            targets = Target.<Target>find("#Target.unconnected").list();
        } else {
            targets = Target.listAll();
        }

        targets.stream()
                .peek(t -> logger.debugv("JVM ID for {0} = {1}", t.connectUrl, t.jvmId))
                .distinct()
                .forEach(t -> executor.submit(() -> updateTarget(t)));
    }

    private void updateTarget(Target target) {
        boolean b = true;
        if (StringUtils.isBlank(target.jvmId)) {
            b = updateTargetJvmId(target);
        }
        if (b) {
            updateTargetRecordings(target);
        }
    }

    private boolean updateTargetJvmId(Target target) {
        final String jvmId =
                connectionManager
                        .executeConnectedTask(
                                QuarkusTransaction.joiningExisting()
                                        .call(() -> Target.getTargetById(target.id)),
                                JFRConnection::getJvmIdentifier)
                        .getHash();
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            Target t = Target.getTargetById(target.id);
                            try {
                                t.jvmId = jvmId;
                                logger.debugv(
                                        "Updated JVM ID for target {0} ({1}) = {2}",
                                        target.connectUrl, target.alias, t.jvmId);
                                return true;
                            } catch (PersistenceException e) {
                                t.jvmId = null;
                                t.persist();
                                logger.warn(e);
                                return false;
                            } catch (Exception e) {
                                t.jvmId = null;
                                t.persist();
                                logger.error(e);
                                throw e;
                            }
                        });
    }

    private void updateTargetRecordings(Target target) {
        QuarkusTransaction.joiningExisting()
                .run(
                        () -> {
                            Target t = Target.getTargetById(target.id);
                            t.activeRecordings = recordingHelper.syncActiveRecordings(t);
                            t.persist();

                            t.activeRecordings.stream()
                                    .filter(r -> !r.continuous)
                                    .filter(r -> !RecordingState.CLOSED.equals(r.state))
                                    .filter(r -> !RecordingState.STOPPED.equals(r.state))
                                    .forEach(
                                            r -> {
                                                try {
                                                    updateService.fireActiveRecordingUpdate(r);
                                                } catch (SchedulerException e) {
                                                    logger.error(e);
                                                }
                                            });
                        });
    }
}
