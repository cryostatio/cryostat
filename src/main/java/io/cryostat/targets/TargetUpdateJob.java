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

import io.cryostat.core.net.JFRConnection;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
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
public class TargetUpdateJob implements Job {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;
    @Inject TargetUpdateService updateService;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        List<Target> targets;
        Long targetId = (Long) context.getJobDetail().getJobDataMap().get("targetId");
        if (targetId != null) {
            try {
                targets = List.of(Target.getTargetById(targetId));
            } catch (PersistenceException e) {
                // target disappeared in the meantime. No big deal.
                logger.debug(e);
                return;
            }
        } else {
            targets = Target.<Target>find("#Target.unconnected").list();
        }
        targets.stream()
                .filter(t -> StringUtils.isBlank(t.jvmId))
                .forEach(t -> Infrastructure.getDefaultExecutor().execute(() -> updateTarget(t)));
    }

    private void updateTarget(Target target) {
        final String jvmId =
                connectionManager
                        .executeConnectedTask(target, JFRConnection::getJvmIdentifier)
                        .getHash();
        QuarkusTransaction.joiningExisting()
                .run(
                        () -> {
                            Target t = Target.getTargetById(target.id);
                            try {
                                t.jvmId = jvmId;
                            } catch (PersistenceException e) {
                                t.jvmId = null;
                                t.persist();
                                logger.warn(e);
                                return;
                            } catch (Exception e) {
                                t.jvmId = null;
                                t.persist();
                                throw e;
                            }
                            t.activeRecordings = recordingHelper.listActiveRecordings(t);
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
