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

import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.ObjectDeletedException;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Attempt to connect to a remote target JVM to determine if detected recordings (not initiated by
 * Cryostat) complete at the end of the expected duration.
 *
 * @see io.cryostat.targets.Target
 * @see io.cryostat.targets.TargetUpdateJob
 */
@DisallowConcurrentExecution
public class ActiveRecordingUpdateJob implements Job {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        long recordingId = (long) context.getMergedJobDataMap().get("recordingId");
        ActiveRecording recording = ActiveRecording.findById(recordingId);
        Target target;
        try {
            target = Target.getTargetById(recording.target.id);
        } catch (NoResultException | ObjectDeletedException e) {
            // target disappeared in the meantime. No big deal.
            logger.debug(e);
            JobExecutionException ex = new JobExecutionException(e);
            ex.setRefireImmediately(false);
            ex.setUnscheduleFiringTrigger(true);
            throw ex;
        } catch (PersistenceException e) {
            JobExecutionException ex = new JobExecutionException(e);
            ex.setRefireImmediately(false);
            throw ex;
        }
        // FIXME hacky. This opens a remote connection on each call and updates our database with
        // the data we find there. We should have some remote connection callback (JMX listener,
        // WebSocket) to the target and update our database when remote recording events occur,
        // rather than doing a full sync when this method is called.
        // TODO retry logic if the expected result is not observed (ie recording is still running
        // somehow)
        recordingHelper.syncActiveRecordings(target);
    }
}
