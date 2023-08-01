/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.rules;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

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
        try {
            var rule = (Rule) ctx.getJobDetail().getJobDataMap().get("rule");
            var target = (Target) ctx.getJobDetail().getJobDataMap().get("target");
            var recording = (ActiveRecording) ctx.getJobDetail().getJobDataMap().get("recording");

            Queue<String> previousRecordings = new ArrayDeque<>(rule.preservedArchives);

            initPreviousRecordings(target, rule, previousRecordings);

            while (previousRecordings.size() >= rule.preservedArchives) {
                pruneArchive(target, previousRecordings, previousRecordings.remove());
            }
            performArchival(target, recording, previousRecordings);
        } catch (Exception e) {
            logger.error(e);
            // TODO: Handle JMX/SSL errors
        }
    }

    private void initPreviousRecordings(
            Target target, Rule rule, Queue<String> previousRecordings) {
        recordingHelper.listArchivedRecordingObjects().parallelStream()
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

    private void performArchival(
            Target target, ActiveRecording recording, Queue<String> previousRecordings)
            throws Exception {
        String filename = recordingHelper.saveRecording(target, recording);
        previousRecordings.add(filename);
    }

    private void pruneArchive(Target target, Queue<String> previousRecordings, String filename)
            throws Exception {
        recordingHelper.deleteArchivedRecording(target.jvmId, filename);
        previousRecordings.remove(filename);
    }
}
