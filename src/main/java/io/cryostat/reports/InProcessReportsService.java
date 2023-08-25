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
package io.cryostat.reports;

import java.io.BufferedInputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

@Singleton
public class InProcessReportsService implements ReportsService {

    @Inject RecordingHelper helper;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject Logger logger;

    @Override
    public Future<Map<String, RuleEvaluation>> reportFor(ActiveRecording recording) {
        try {
            return reportGenerator.generateEvalMapInterruptibly(
                    new BufferedInputStream(helper.getActiveInputStream(recording)), r -> true);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public Future<Map<String, RuleEvaluation>> reportFor(String jvmId, String filename) {
        return reportGenerator.generateEvalMapInterruptibly(
                new BufferedInputStream(helper.getArchivedRecordingStream(jvmId, filename)),
                r -> true);
    }
}
