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

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.cryostat.recordings.RecordingHelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.ObjectDeletedException;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Synchronize the activeRecordings state for a remote target JVM by querying the target connection.
 */
@DisallowConcurrentExecution
public class TargetUpdateJob implements Job {

    @Inject Logger logger;
    @Inject RecordingHelper recordingHelper;
    @Inject TargetUpdateService updateService;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String targetIdStr = (String) context.getMergedJobDataMap().get("targetId");
        try {
            Target target =
                    QuarkusTransaction.joiningExisting()
                            .call(() -> Target.getTargetById(UUID.fromString(targetIdStr)));
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

    private void updateTargetRecordings(Target target) {
        QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            Target t = Target.getTargetById(target.id);
                            t.activeRecordings = recordingHelper.syncActiveRecordings(t);
                            t.persist();
                            return t.activeRecordings;
                        })
                .stream()
                .forEach(updateService::fireActiveRecordingUpdate);
    }
}
