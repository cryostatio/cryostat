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
import java.util.List;
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
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/metrics/reports")
public class AnalysisReportAggregator {

    @Inject EventBus bus;
    @Inject Logger logger;

    private final Map<String, List<Pair<String, String>>> ownerChains = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AnalysisResult>> reports = new ConcurrentHashMap<>();

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
            ownerChains.put(target.jvmId, ownerChain(target));
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
                reports.put(target.jvmId, report);
            } catch (Exception e) {
                logger.warn(e);
                reports.remove(jvmId);
                ownerChains.remove(jvmId);
            }
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    // TODO should this include results from lost targets?
    public String scrape() {
        var sb = new StringBuilder();
        reports.forEach((id, r) -> sb.append(stringify(id, r)));
        return sb.toString();
    }

    @GET
    @Path("/{jvmId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    // TODO should this include results from lost targets?
    public String scrape(@RestPath String jvmId) {
        var report = reports.get(jvmId);
        if (report == null) {
            throw new NotFoundException();
        }
        return stringify(jvmId, report);
    }

    private String stringify(String jvmId, Map<String, AnalysisResult> report) {
        var sb = new StringBuilder();
        report.forEach(
                (k, v) ->
                        sb.append(k.replaceAll("[\\.\\s]+", "_"))
                                .append(chainToLabels(ownerChains.get(jvmId)))
                                .append('=')
                                .append(v.getScore())
                                .append('\n'));
        return sb.toString();
    }

    private static String chainToLabels(List<Pair<String, String>> chain) {
        return "{"
                + String.join(
                        ", ",
                        chain.stream()
                                .map(p -> String.format("%s=\"%s\"", p.getKey(), p.getValue()))
                                .toList())
                + "}";
    }

    private static List<Pair<String, String>> ownerChain(Target target) {
        var ownerChain = new Stack<DiscoveryNode>();
        var node = target.discoveryNode;
        while (node != null && !node.nodeType.equals(BaseNodeType.UNIVERSE.getKind())) {
            ownerChain.push(node);
            node = node.parent;
        }
        var list = new ArrayList<Pair<String, String>>();
        while (!ownerChain.isEmpty()) {
            var n = ownerChain.pop();
            list.add(Pair.of(n.nodeType, n.name));
        }
        list.add(Pair.of("jvmId", target.jvmId));
        return list;
    }
}
