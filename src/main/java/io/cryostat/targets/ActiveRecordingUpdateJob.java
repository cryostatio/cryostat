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
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
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
public class ActiveRecordingUpdateJob implements Job {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long recordingId = (Long) context.getJobDetail().getJobDataMap().get("recordingId");
        ActiveRecording recording = ActiveRecording.findById(recordingId);
        Target target;
        try {
            target = Target.getTargetById(recording.target.id);
        } catch (PersistenceException e) {
            // the target was lost in the meantime, so we can stop worrying about this update
            logger.debug(e);
            return;
        }
        // TODO retry logic if the expected result is not observed (ie recording is still running
        // somehow)
        recordingHelper.listActiveRecordings(target);
    }
}
