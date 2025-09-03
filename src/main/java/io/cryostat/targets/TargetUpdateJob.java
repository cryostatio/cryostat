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
import java.util.concurrent.Executor;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

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

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        List<Target> targets;
        Long targetId = (Long) context.getJobDetail().getJobDataMap().get("targetId");
        try {
            if (targetId != null) {
                targets = List.of(Target.getTargetById(targetId));
            } else {
                targets = Target.<Target>find("#Target.unconnected").list();
            }
        } catch (PersistenceException e) {
            // target disappeared in the meantime. No big deal.
            logger.debug(e);
            return;
        }

        Executor executor;
        if (targets.size() == 1) {
            executor = Runnable::run;
        } else {
            executor = Infrastructure.getDefaultExecutor();
        }
        targets.forEach(t -> executor.execute(() -> updateTargetTx(t.id)));
    }

    private void updateTargetTx(long id) {
        QuarkusTransaction.requiringNew().run(() -> updateTarget(Target.getTargetById(id)));
    }

    private void updateTarget(Target target) {
        try {
            target.jvmId =
                    connectionManager
                            .executeConnectedTask(target, JFRConnection::getJvmIdentifier)
                            .getHash();
        } catch (PersistenceException e) {
            target.jvmId = null;
            target.persist();
            logger.debug(e);
            return;
        } catch (Exception e) {
            target.jvmId = null;
            target.persist();
            throw e;
        }
        target.activeRecordings = recordingHelper.listActiveRecordings(target);
        target.persist();
    }
}
