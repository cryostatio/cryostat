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
package io.cryostat.rules;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Perform recording archival by pulling data stream from a target and copying it into a file in S3
 * object storage.
 *
 * @see io.cryostat.target.Target
 * @see io.cryostat.recordings.ActiveRecording
 * @see io.cryostat.rules.Rule
 * @see io.cryostat.rules.RuleExecutor
 */
class ScheduledArchiveJob implements Job {

    private static final Pattern RECORDING_FILENAME_PATTERN =
            Pattern.compile(
                    "([A-Za-z\\d\\.-]*)_([A-Za-z\\d-_]*)_([\\d]*T[\\d]*Z)(\\.[\\d]+)?(\\.jfr)?");

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        var entry =
                QuarkusTransaction.joiningExisting()
                        .call(
                                () -> {
                                    long ruleId =
                                            (long) ctx.getJobDetail().getJobDataMap().get("rule");
                                    Rule rule = Rule.find("id", ruleId).singleResult();
                                    long targetId =
                                            (long) ctx.getJobDetail().getJobDataMap().get("target");
                                    Target target = Target.find("id", targetId).singleResult();
                                    long recordingId =
                                            (long)
                                                    ctx.getJobDetail()
                                                            .getJobDataMap()
                                                            .get("recording");
                                    ActiveRecording recording =
                                            recordingHelper
                                                    .getActiveRecording(target, recordingId)
                                                    .orElseThrow();

                                    if (recording == null) {
                                        throw new IllegalStateException(
                                                String.format(
                                                        "Target %s did not have recording with"
                                                                + " remote ID %d",
                                                        target.connectUrl, recordingId));
                                    }

                                    return new Entry(target, rule, recording);
                                });

        Queue<String> previousRecordings = new ArrayDeque<>(entry.rule.preservedArchives);
        initPreviousRecordings(entry.target, entry.rule, previousRecordings);
        while (previousRecordings.size() >= entry.rule.preservedArchives) {
            pruneArchive(entry.target, previousRecordings, previousRecordings.remove());
        }

        try {
            previousRecordings.add(recordingHelper.archiveRecording(entry.recording).name());
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    void initPreviousRecordings(Target target, Rule rule, Queue<String> previousRecordings) {
        recordingHelper.listArchivedRecordingObjects().stream()
                .sorted((a, b) -> a.lastModified().compareTo(b.lastModified()))
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            if (jvmId.equals(target.jvmId)) {
                                String filename = parts[1];
                                Matcher m = RECORDING_FILENAME_PATTERN.matcher(filename);
                                if (m.matches()) {
                                    String recordingName = m.group(2);
                                    if (Objects.equals(recordingName, rule.getRecordingName())) {
                                        previousRecordings.add(filename);
                                    }
                                }
                            }
                        });
    }

    void pruneArchive(Target target, Queue<String> previousRecordings, String filename) {
        try {
            recordingHelper.deleteArchivedRecording(target.jvmId, filename);
        } catch (IOException e) {
            logger.error(e);
        }
        previousRecordings.remove(filename);
    }

    record Entry(Target target, Rule rule, ActiveRecording recording) {}
}
