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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.discovery.NodeType.BaseNodeType;
import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ActiveReportCompletion;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchivedReportCompletion;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchivedReportRequest;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.EventKind;
import io.cryostat.targets.Target.TargetDiscovery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v4.1/metrics/reports")
public class AnalysisReportAggregator {

    @Inject EventBus bus;
    @Inject Logger logger;

    public static final String AUTOANALYZE_LABEL = "autoanalyze";
    static final String AGGREGATOR_CACHE_NAME = "reports-aggregator";

    @Inject
    @CacheName(AGGREGATOR_CACHE_NAME)
    Cache cache;

    @Inject RecordingHelper recordingHelper;

    @ConsumeEvent(value = ActiveRecordings.ARCHIVED_RECORDING_CREATED, blocking = true)
    @Transactional
    public void onMessage(ArchivedRecording recording) {
        var autoanalyze = recording.metadata().labels().get(AUTOANALYZE_LABEL);
        if (Boolean.parseBoolean(autoanalyze)) {
            var jvmId = recording.jvmId();
            getOrCreateEntry(jvmId)
                    .subscribe()
                    .with(
                            entry -> {
                                if (recording.archivedTime() < entry.timestamp()) {
                                    // cached data is fresher
                                    return;
                                }
                                var filename = recording.name();
                                logger.tracev(
                                        "Triggering batch report processing for {0}/{1}.",
                                        jvmId, filename);
                                var request =
                                        new ArchivedReportRequest(
                                                UUID.randomUUID().toString(),
                                                Pair.of(jvmId, filename));
                                try {
                                    var future = new CompletableFuture<Entry>();
                                    bus.<Map<String, AnalysisResult>>request(
                                                    LongRunningRequestGenerator
                                                            .ARCHIVE_REPORT_REQUEST_ADDRESS,
                                                    request)
                                            .subscribe()
                                            .with(
                                                    report ->
                                                            future.complete(
                                                                    new Entry(
                                                                            recording
                                                                                    .archivedTime(),
                                                                            entry.ownerChain(),
                                                                            report.body())));
                                    cache.as(CaffeineCache.class).put(jvmId, future);
                                } catch (Exception e) {
                                    logger.warn(e);
                                    cache.invalidate(jvmId).await().atMost(Duration.ofMillis(100));
                                }
                            });
        }
    }

    @ConsumeEvent(
            value = LongRunningRequestGenerator.ACTIVE_REPORT_COMPLETE_ADDRESS,
            blocking = true)
    @Transactional
    public void onMessage(ActiveReportCompletion evt) {
        var jvmId = evt.recording().target.jvmId;
        getOrCreateEntry(jvmId)
                .subscribe()
                .with(
                        entry -> {
                            long now = Instant.now().getEpochSecond();
                            if (now < entry.timestamp()) {
                                // cached data is fresher
                                return;
                            }
                            cache.as(CaffeineCache.class)
                                    .put(
                                            jvmId,
                                            CompletableFuture.completedFuture(
                                                    new Entry(
                                                            now,
                                                            entry.ownerChain(),
                                                            evt.report())));
                        });
    }

    @ConsumeEvent(
            value = LongRunningRequestGenerator.ARCHIVED_REPORT_COMPLETE_ADDRESS,
            blocking = true)
    @Transactional
    public void onMessage(ArchivedReportCompletion evt) {
        var jvmId = evt.jvmId();
        var filename = evt.filename();
        getOrCreateEntry(jvmId)
                .subscribe()
                .with(
                        entry -> {
                            recordingHelper
                                    .getArchivedRecordingInfo(jvmId, filename)
                                    .ifPresent(
                                            archivedRecording -> {
                                                if (archivedRecording.archivedTime()
                                                        < entry.timestamp()) {
                                                    // cached data is fresher
                                                    return;
                                                }
                                                cache.as(CaffeineCache.class)
                                                        .put(
                                                                jvmId,
                                                                CompletableFuture.completedFuture(
                                                                        new Entry(
                                                                                archivedRecording
                                                                                        .archivedTime(),
                                                                                entry.ownerChain(),
                                                                                evt.report())));
                                            });
                        });
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    void onMessage(TargetDiscovery event) {
        if (EventKind.LOST.equals(event.kind())) {
            cache.invalidate(event.serviceRef().jvmId).await().atMost(Duration.ofMillis(100));
        }
    }

    public void reset() {
        cache.invalidateAll().await().atMost(Duration.ofMillis(100));
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("read")
    @Transactional
    @Operation(
            summary = "Retrieve the latest aggregate report data",
            description =
                    """
                    Retrieve the latest aggregate report data across all targets with recent automated analysis reports
                    scores. These are multi-dimensional metrics in Prometheus format.
                    """)
    // TODO should this include results from lost targets?
    public Multi<String> scrape() {
        var multis =
                cache.as(CaffeineCache.class).keySet().stream()
                        .map(
                                k ->
                                        getOrCreateEntry((String) k)
                                                .onItem()
                                                .transform(this::stringify)
                                                .toMulti())
                        .toList();
        return Multi.createBy().concatenating().streams(multis);
    }

    @GET
    @Path("/{jvmId}")
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("read")
    @Transactional
    @Operation(
            summary = "Retrieve the latest aggregate report data for the specified target",
            description =
                    """
                    Retrieve the latest aggregate report data for a given target's recent automated analysis reports
                    scores. These are multi-dimensional metrics in Prometheus format.
                    """)
    // TODO should this include results from lost targets?
    public Uni<RestResponse<String>> scrape(@RestPath String jvmId) {
        return getEntry(jvmId)
                .onItem()
                .transform(
                        e -> {
                            var builder =
                                    RestResponse.ResponseBuilder.<String>create(200)
                                            .entity(stringify(e));
                            var timestamp = e.timestamp();
                            if (timestamp > 0) {
                                builder.lastModified(Date.from(Instant.ofEpochSecond(timestamp)));
                            }
                            return builder.build();
                        });
    }

    public Uni<Entry> getEntry(String jvmId) {
        if (StringUtils.isBlank(jvmId)) {
            return Uni.createFrom().failure(() -> new NotFoundException());
        }
        CompletableFuture<Entry> f = cache.as(CaffeineCache.class).getIfPresent(jvmId);
        if (f == null) {
            return Uni.createFrom().failure(() -> new NotFoundException());
        }
        return Uni.createFrom().future(f);
    }

    private String stringify(Entry entry) {
        var sb = new StringBuilder();
        entry.report()
                .forEach(
                        (k, v) ->
                                sb.append(k.replaceAll("[\\.\\s]+", "_"))
                                        .append(chainToLabels(entry.ownerChain()))
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

    private Uni<Entry> getOrCreateEntry(String jvmId) {
        return cache.get(
                jvmId,
                k -> {
                    return QuarkusTransaction.joiningExisting()
                            .call(
                                    () -> {
                                        var target = Target.getTargetByJvmId(k).orElseThrow();
                                        return new Entry(ownerChain(target));
                                    });
                });
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP"})
    public record Entry(
            long timestamp,
            List<Pair<String, String>> ownerChain,
            Map<String, AnalysisResult> report) {
        public Entry {
            Objects.requireNonNull(ownerChain);
            Objects.requireNonNull(report);
            ownerChain = new ArrayList<>(ownerChain);
            report = new HashMap<>(report);
        }

        public Entry(List<Pair<String, String>> ownerChain) {
            this(0, ownerChain, Map.of());
        }
    }
}
