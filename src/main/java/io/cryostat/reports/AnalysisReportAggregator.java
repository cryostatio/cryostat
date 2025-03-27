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
import java.util.HashMap;
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
import io.cryostat.targets.Target.EventKind;
import io.cryostat.targets.Target.TargetDiscovery;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
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

@Path("/api/v4/metrics/reports")
public class AnalysisReportAggregator {

    @Inject EventBus bus;
    @Inject Logger logger;

    public static final String AUTOANALYZE_LABEL = "autoanalyze";

    private final Map<String, List<Pair<String, String>>> ownerChains = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AnalysisResult>> reports = new ConcurrentHashMap<>();

    @ConsumeEvent(value = ActiveRecordings.ARCHIVED_RECORDING_CREATED, blocking = true)
    @Transactional
    public void onMessage(ArchivedRecording recording) {
        var autoanalyze = recording.metadata().labels().get(AUTOANALYZE_LABEL);
        if (Boolean.parseBoolean(autoanalyze)) {
            var id = UUID.randomUUID();
            var jvmId = recording.jvmId();
            try {
                var target = Target.getTargetByJvmId(jvmId).orElseThrow();
                ownerChains.put(target.jvmId, ownerChain(target));
                var filename = recording.name();
                logger.tracev("Triggering batch report processing for {0}/{1}.", jvmId, filename);
                var request = new ArchivedReportRequest(id.toString(), Pair.of(jvmId, filename));
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

    @ConsumeEvent(Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) {
        if (EventKind.LOST.equals(event.kind())) {
            var jvmId = event.serviceRef().jvmId;
            reports.remove(jvmId);
            ownerChains.remove(jvmId);
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("read")
    @Transactional
    // TODO should this include results from lost targets?
    public Multi<String> scrape() {
        return Multi.createFrom()
                .items(reports.entrySet().stream().map(e -> stringify(e.getKey(), e.getValue())));
    }

    @GET
    @Path("/{jvmId}")
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("read")
    @Transactional
    // TODO should this include results from lost targets?
    public String scrape(@RestPath String jvmId) {
        var report = getReport(jvmId);
        return stringify(jvmId, report);
    }

    public Map<String, AnalysisResult> getReport(String jvmId) {
        var r = reports.get(jvmId);
        if (r == null) {
            throw new NotFoundException();
        }
        return new HashMap<>(r);
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
