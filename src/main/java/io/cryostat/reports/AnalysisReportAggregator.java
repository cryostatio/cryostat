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

import java.util.ArrayList;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.discovery.NodeType.BaseNodeType;
import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchivedReportRequest;
import io.cryostat.targets.Target;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;

@Path("/metrics/reports")
public class AnalysisReportAggregator {

    @Inject EventBus bus;
    @Inject Logger logger;

    private final Map<Target, Map<String, AnalysisResult>> map = new ConcurrentHashMap<>();

    @ConsumeEvent(value = ActiveRecordings.ARCHIVED_RECORDING_CREATED, blocking = true)
    @Transactional
    public void onMessage(ArchivedRecording recording) {
        // TODO extract these to constants, and/or use other labelling
        var key = "origin";
        var value = "automated-analysis";
        var origin = recording.metadata().labels().get(key);
        if (value.equals(origin)) {
            var id = UUID.randomUUID();
            var jvmId = recording.jvmId();
            var target = Target.getTargetByJvmId(jvmId).orElseThrow();
            var filename = recording.name();
            logger.tracev(
                    "Archived recording with {0}={1} label observed. Triggering batch report"
                            + " processing for {2}/{3}.",
                    key, value, jvmId, filename);
            var request = new ArchivedReportRequest(id.toString(), Pair.of(jvmId, filename));
            try {
                var report =
                        bus.<Map<String, AnalysisResult>>requestAndAwait(
                                        LongRunningRequestGenerator.ARCHIVE_REPORT_ADDRESS, request)
                                .body();
                update(target, report);
            } catch (Exception e) {
                logger.warn(e);
            }
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    @Blocking
    public String scrape() {
        var sb = new StringBuilder();
        map.forEach(
                (t, r) ->
                        r.forEach(
                                (k, v) ->
                                        sb.append(k.replaceAll("\\.", "_"))
                                                .append('{')
                                                .append(nodeLabels(t.id))
                                                .append('}')
                                                .append('=')
                                                .append(v.getScore())
                                                .append('\n')));
        return sb.toString();
    }

    private void update(Target target, Map<String, AnalysisResult> report) {
        map.put(target, report);
    }

    private static String nodeLabels(long targetId) {
        var target = Target.<Target>findById(targetId);
        var ownerChain = new Stack<DiscoveryNode>();
        var node = target.discoveryNode;
        while (node != null && !node.nodeType.equals(BaseNodeType.UNIVERSE.getKind())) {
            ownerChain.push(node);
            node = node.parent;
        }
        var list = new ArrayList<String>();
        while (!ownerChain.isEmpty()) {
            var n = ownerChain.pop();
            list.add(String.format("%s=\"%s\"", n.nodeType, n.name));
        }
        list.add(String.format("jvmId=\"%s\"", target.jvmId));
        return String.join(", ", list);
    }
}
