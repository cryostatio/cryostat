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

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.handler.HttpException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.jboss.logging.Logger;

class SidecarReportsService implements ReportsService {

    static final String URL_CONFIG_PROPERTY = "cryostat.services.reports.url";
    private final URI sidecarUri;
    private final ObjectMapper mapper;
    private final RecordingHelper helper;
    private final Logger logger = Logger.getLogger(SidecarReportsService.class.getName());

    SidecarReportsService(URI sidecarUri, ObjectMapper mapper, RecordingHelper helper) {
        this.sidecarUri = sidecarUri;
        this.mapper = mapper;
        this.helper = helper;
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        logger.infov(
                "sidecar reportFor active recording {0} {1}",
                recording.target.jvmId, recording.remoteId);
        var cf = new CompletableFuture<Map<String, RuleEvaluation>>();
        try {
            fireRequest(cf, helper.getActiveInputStream(recording));
        } catch (Exception e) {
            cf.completeExceptionally(e);
        }
        return Uni.createFrom().future(cf);
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        logger.infov("sidecar reportFor archived recording {0} {1}", jvmId, filename);
        var cf = new CompletableFuture<Map<String, RuleEvaluation>>();
        try {
            fireRequest(cf, helper.getArchivedRecordingStream(jvmId, filename));
        } catch (Exception e) {
            cf.completeExceptionally(e);
        }
        return Uni.createFrom().future(cf);
    }

    private void fireRequest(
            CompletableFuture<Map<String, RuleEvaluation>> cf, InputStream stream) {
        try (var http = HttpClients.createDefault();
                stream) {
            var post = new HttpPost(sidecarUri.resolve("report"));
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
    }
}
