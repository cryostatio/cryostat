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
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.handler.HttpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
class ReportsServiceImpl implements ReportsService {

    static final String URL_CONFIG_PROPERTY = "cryostat.services.reports.url";

    @ConfigProperty(name = URL_CONFIG_PROPERTY)
    Optional<URI> sidecarUri;

    @Inject ObjectMapper mapper;
    @Inject RecordingHelper helper;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject Logger logger;

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        return sidecarUri
                .map(
                        uri -> {
                            logger.tracev(
                                    "sidecar reportFor active recording {0} {1}",
                                    recording.target.jvmId, recording.remoteId);
                            try {
                                return fireRequest(uri, helper.getActiveInputStream(recording));
                            } catch (Exception e) {
                                return Uni.createFrom().<Map<String, RuleEvaluation>>failure(e);
                            }
                        })
                .orElseGet(
                        () -> {
                            logger.tracev(
                                    "inprocess reportFor active recording {0} {1}",
                                    recording.target.jvmId, recording.remoteId);
                            try {
                                return process(helper.getActiveInputStream(recording), predicate);
                            } catch (Exception e) {
                                return Uni.createFrom().<Map<String, RuleEvaluation>>failure(e);
                            }
                        });
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        return sidecarUri
                .map(
                        uri -> {
                            logger.tracev(
                                    "sidecar reportFor archived recording {0} {1}",
                                    jvmId, filename);
                            return fireRequest(
                                    uri, helper.getArchivedRecordingStream(jvmId, filename));
                        })
                .orElseGet(
                        () -> {
                            logger.tracev(
                                    "inprocess reportFor archived recording {0} {1}",
                                    jvmId, filename);
                            return process(
                                    helper.getArchivedRecordingStream(jvmId, filename), predicate);
                        });
    }

    private Uni<Map<String, RuleEvaluation>> process(
            InputStream stream, Predicate<IRule> predicate) {
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(stream), predicate))
                .map(
                        result ->
                                result.entrySet().stream()
                                        .collect(
                                                Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        e -> RuleEvaluation.from(e.getValue()))));
    }

    private Uni<Map<String, RuleEvaluation>> fireRequest(URI uri, InputStream stream) {
        var cf = new CompletableFuture<Map<String, RuleEvaluation>>();
        try (var http = HttpClients.createDefault();
                stream) {
            var post = new HttpPost(uri.resolve("report"));
            var form = MultipartEntityBuilder.create().addBinaryBody("file", stream).build();
            post.setEntity(form);
            http.execute(
                    post,
                    response -> {
                        if (!HttpStatusCodeIdentifier.isSuccessCode(response.getCode())) {
                            cf.completeExceptionally(
                                    new HttpException(
                                            response.getCode(), response.getReasonPhrase()));
                            return null;
                        }
                        var entity = response.getEntity();
                        Map<String, RuleEvaluation> evaluation =
                                mapper.readValue(
                                        entity.getContent(),
                                        new TypeReference<Map<String, RuleEvaluation>>() {});
                        cf.complete(evaluation);
                        return null;
                    });
        } catch (Exception e) {
            cf.completeExceptionally(e);
        }
        return Uni.createFrom().future(cf);
    }
}
