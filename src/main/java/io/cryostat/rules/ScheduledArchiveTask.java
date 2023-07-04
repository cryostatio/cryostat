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

import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;

class ScheduledArchiveTask implements Runnable {

    private static final Pattern RECORDING_FILENAME_PATTERN =
            Pattern.compile(
                    "([A-Za-z\\d-]*)_([A-Za-z\\d-_]*)_([\\d]*T[\\d]*Z)(\\.[\\d]+)?(\\.jfr)?");
    private final Rule rule;
    private final Target target;
    private final ActiveRecording recording;
    private final Queue<String> previousRecordings;
    private final S3Client storage;
    private final RecordingHelper recordingHelper;
    private final Logger logger;

    @ConfigProperty(name = "storage.buckets.archives.name")
    String archiveBucket;

    ScheduledArchiveTask(
            S3Client storage,
            RecordingHelper recordingHelper,
            Logger logger,
            Rule rule,
            Target target,
            ActiveRecording recording) {
        this.storage = storage;
        this.recordingHelper = recordingHelper;
        this.logger = logger;
        this.rule = rule;
        this.target = target;
        this.recording = recording;
        this.previousRecordings = new ArrayDeque<>(rule.preservedArchives);
        this.archiveBucket =
                ConfigProvider.getConfig().getValue("storage.buckets.archives.name", String.class);
    }

    @Override
    public void run() {
        try {
            logger.infov("Archival task for rule {0} on {1}", rule.name, target.alias);
            // If there are no previous recordings, either this is the first time this rule is being
            // archived or the Cryostat instance was restarted. Since it could be the latter,
            // populate the array with any previously archived recordings for this rule.
            if (previousRecordings.isEmpty()) {
                initPreviousRecordings();
            }
            while (previousRecordings.size() >= rule.preservedArchives) {
                pruneArchive(previousRecordings.remove());
            }
            performArchival();
        } catch (Exception e) {
            logger.error(e);
            // TODO: Handle JMX/SSL errors
        }
    }

    private void initPreviousRecordings() {
      System.out.println("Listing recordings...");
        System.out.println(recordingHelper.listArchivedRecordingObjects());
        recordingHelper.listArchivedRecordingObjects()
                .parallelStream()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            System.out.println(jvmId);
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

    private void performArchival() throws Exception {
        String filename = recordingHelper.saveRecording(target, recording);
        previousRecordings.add(filename);
    }

    private void pruneArchive(String filename) throws Exception {
        recordingHelper.deleteArchivedRecording(target.jvmId, filename);
        previousRecordings.remove(filename);
    }
}
