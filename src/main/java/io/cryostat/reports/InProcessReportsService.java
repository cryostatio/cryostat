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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

class InProcessReportsService implements ReportsService {

    private final RecordingHelper helper;
    private final InterruptibleReportGenerator reportGenerator;
    private final Logger logger = Logger.getLogger(InProcessReportsService.class.getName());

    InProcessReportsService(RecordingHelper helper, InterruptibleReportGenerator reportGenerator) {
        this.helper = helper;
        this.reportGenerator = reportGenerator;
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        try {
            logger.tracev(
                    "inprocess reportFor active recording {0} {1}",
                    recording.target.jvmId, recording.remoteId);
            return Uni.createFrom()
                    .future(
                            reportGenerator.generateEvalMapInterruptibly(
                                    new BufferedInputStream(helper.getActiveInputStream(recording)),
                                    predicate))
                    .map(
                            result ->
                                    result.entrySet().stream()
                                            .collect(
                                                    Collectors.toMap(
                                                            Map.Entry::getKey,
                                                            e ->
                                                                    RuleEvaluation.from(
                                                                            e.getValue()))));
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        logger.tracev("inprocess reportFor archived recording {0} {1}", jvmId, filename);
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(
                                        helper.getArchivedRecordingStream(jvmId, filename)),
                                predicate))
                .map(
                        result ->
                                result.entrySet().stream()
                                        .collect(
                                                Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        e -> RuleEvaluation.from(e.getValue()))));
    }
}
