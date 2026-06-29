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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.ObjectDeletedException;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

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
        Target target;
        long targetId = (long) context.getMergedJobDataMap().get("targetId");
        try {
            target = Target.getTargetById(targetId);
            if (StringUtils.isBlank(target.jvmId)) {
                updateTargetJvmId(target);
            }
            updateTargetRecordings(target);
        } catch (Exception e) {
            boolean targetLost =
                    ExceptionUtils.indexOfType(e, NoResultException.class) >= 0
                            || ExceptionUtils.indexOfType(e, ObjectDeletedException.class) >= 0;
            if (targetLost) {
                // target disappeared in the meantime. No big deal.
                logger.debug(e);
                JobExecutionException ex = new JobExecutionException(e);
                ex.setRefireImmediately(false);
                ex.setUnscheduleFiringTrigger(true);
                throw ex;
            }
            if (ExceptionUtils.indexOfType(e, PersistenceException.class) >= 0) {
                JobExecutionException ex = new JobExecutionException(e);
                ex.setRefireImmediately(false);
                throw ex;
            }
            logger.warn(e);
            throw e;
        }
    }

    private void updateTargetJvmId(Target target) {
        final String jvmId =
                connectionManager
                        .executeConnectedTask(
                                QuarkusTransaction.joiningExisting()
                                        .call(() -> Target.getTargetById(target.id)),
                                JFRConnection::getJvmIdentifier)
                        .getHash();
        QuarkusTransaction.joiningExisting()
                .run(
                        () -> {
                            Target t = Target.getTargetById(target.id);
                            try {
                                t.jvmId = jvmId;
                                logger.debugv(
                                        "Updated JVM ID for target {0} ({1}) = {2}",
                                        target.connectUrl, target.alias, t.jvmId);
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
                                    .forEach(updateService::fireActiveRecordingUpdate);
                        });
    }
}
