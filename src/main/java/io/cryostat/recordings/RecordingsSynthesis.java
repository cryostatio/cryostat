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
package io.cryostat.recordings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.LongRunningRequestGenerator.SynthesisRequest;
import io.cryostat.targets.Target;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@ApplicationScoped
@Path("")
public class RecordingsSynthesis {

    @Inject RecordingHelper recordingHelper;
    @Inject EventBus bus;
    @Inject EntityManager em;
    @Inject Logger logger;

    @POST
    @Blocking
    @Transactional
    @RolesAllowed("write")
    @Path("/api/beta/recording_synthesis/{jvmId}")
    public Response synthesize(
            HttpServerResponse response,
            @RestPath String jvmId,
            @QueryParam("fromTimestamp") long fromTimestamp,
            @QueryParam("toTimestamp") long toTimestamp,
            @QueryParam("tag") String tag) {

        if (fromTimestamp >= toTimestamp) {
            throw new BadRequestException();
        }

        long fromMs = fromTimestamp * 1000L;
        long toMs = toTimestamp * 1000L;

        List<ArchivedRecording> completeCandidates = new ArrayList<>();
        List<ArchivedRecording> incompleteCandidates = new ArrayList<>();
        recordingHelper.listArchivedRecordings(jvmId).stream()
                .filter(r -> !isExcluded(r, fromMs, toMs))
                .forEach(
                        r -> {
                            if (isComplete(r, fromMs, toMs)) {
                                completeCandidates.add(r);
                            } else {
                                incompleteCandidates.add(r);
                            }
                        });

        if (completeCandidates.isEmpty() && incompleteCandidates.isEmpty()) {
            throw new BadRequestException();
        }

        if (!completeCandidates.isEmpty()) {
            ArchivedRecording densest =
                    completeCandidates.stream()
                            .max(Comparator.comparingDouble(RecordingsSynthesis::density))
                            .orElseThrow();
            return Response.ok(densest).build();
        }

        if (incompleteCandidates.size() == 1) {
            return Response.ok(incompleteCandidates.get(0)).build();
        }

        String resolvedTag = resolveTag(jvmId, tag);
        String jobId = UUID.randomUUID().toString();
        incompleteCandidates.sort(Comparator.comparingLong(r -> startTimeMs(r)));
        SynthesisRequest request =
                new SynthesisRequest(
                        jobId,
                        jvmId,
                        fromTimestamp,
                        toTimestamp,
                        resolvedTag,
                        incompleteCandidates);
        response.endHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.SYNTHESIS_REQUEST_ADDRESS, request));
        return Response.accepted(jobId).type(MediaType.TEXT_PLAIN).build();
    }

    private String resolveTag(String jvmId, String tagParam) {
        if (StringUtils.isNotBlank(tagParam)) {
            return tagParam;
        }
        var fromTarget = Target.getTargetByJvmId(jvmId).map(t -> t.alias);
        if (fromTarget.isPresent() && StringUtils.isNotBlank(fromTarget.get())) {
            return fromTarget.get();
        }
        try {
            var ar = AuditReaderFactory.get(em);
            @SuppressWarnings("unchecked")
            var q =
                    ar.createQuery()
                            .forRevisionsOfEntity(Target.class, false, true)
                            .add(AuditEntity.property("jvmId").eq(jvmId))
                            .add(
                                    AuditEntity.revisionType()
                                            .ne(org.hibernate.envers.RevisionType.DEL))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (!q.isEmpty()) {
                Object[] result = (Object[]) q.get(0);
                Target t = (Target) result[0];
                if (t != null && StringUtils.isNotBlank(t.alias)) {
                    return t.alias;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to look up target alias from audit for jvmId: " + jvmId, e);
        }
        return jvmId;
    }

    static boolean isExcluded(ArchivedRecording r, long fromMs, long toMs) {
        try {
            long startTime =
                    Long.parseLong(r.metadata().labels().get(RecordingHelper.START_TIME_LABEL));
            long duration =
                    Long.parseLong(r.metadata().labels().get(RecordingHelper.DURATION_LABEL));
            return (startTime + duration) <= fromMs || startTime >= toMs;
        } catch (Exception e) {
            return true;
        }
    }

    static boolean isComplete(ArchivedRecording r, long fromMs, long toMs) {
        try {
            long startTime =
                    Long.parseLong(r.metadata().labels().get(RecordingHelper.START_TIME_LABEL));
            long duration =
                    Long.parseLong(r.metadata().labels().get(RecordingHelper.DURATION_LABEL));
            return startTime <= fromMs && (startTime + duration) >= toMs;
        } catch (Exception e) {
            return false;
        }
    }

    static Predicate<ArchivedRecording> completeFilter(long fromMs, long toMs) {
        return r -> isComplete(r, fromMs, toMs);
    }

    static double density(ArchivedRecording r) {
        try {
            long duration =
                    Long.parseLong(r.metadata().labels().get(RecordingHelper.DURATION_LABEL));
            if (duration == 0) return 0.0;
            return (double) r.size() / duration;
        } catch (Exception e) {
            return 0.0;
        }
    }

    static long startTimeMs(ArchivedRecording r) {
        try {
            return Long.parseLong(r.metadata().labels().get(RecordingHelper.START_TIME_LABEL));
        } catch (Exception e) {
            return 0L;
        }
    }
}
