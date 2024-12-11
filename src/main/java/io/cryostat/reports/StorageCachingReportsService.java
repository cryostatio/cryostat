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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.ConfigProperties;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.util.HttpMimeType;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Priority(20)
@Decorator
@Dependent
class StorageCachingReportsService implements ReportsService {

    @ConfigProperty(name = ConfigProperties.REPORTS_STORAGE_CACHE_ENABLED)
    boolean enabled;

    @ConfigProperty(name = ConfigProperties.ARCHIVED_REPORTS_STORAGE_CACHE_NAME)
    String bucket;

    @ConfigProperty(name = ConfigProperties.ARCHIVED_REPORTS_EXPIRY_DURATION)
    Duration expiry;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @Inject S3Client storage;
    @Inject RecordingHelper recordingHelper;
    @Inject ObjectMapper mapper;

    @Inject @Delegate @Any ReportsService delegate;

    @Inject Logger logger;

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        String key = ReportsService.key(recording);
        logger.tracev("reportFor {0}", key);
        return delegate.reportFor(recording, predicate);
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        if (!enabled) {
            logger.trace("cache disabled, delegating...");
            return delegate.reportFor(jvmId, filename, predicate);
        }
        var key = recordingHelper.archivedRecordingKey(jvmId, filename);
        logger.tracev("reportFor {0}", key);
        return checkStorage(key)
                .onItem()
                .transformToUni(
                        found -> {
                            if (found) {
                                return getStorage(key);
                            } else {
                                return putStorage(
                                        key, delegate.reportFor(jvmId, filename, predicate));
                            }
                        });
    }

    private Uni<Boolean> checkStorage(String key) {
        return Uni.createFrom()
                .item(
                        () -> {
                            var req = HeadObjectRequest.builder().bucket(bucket).key(key).build();
                            try {
                                return storage.headObject(req).sdkHttpResponse().isSuccessful();
                            } catch (NoSuchKeyException nske) {
                                return false;
                            }
                        });
    }

    private Uni<Map<String, AnalysisResult>> putStorage(
            String key, Uni<Map<String, AnalysisResult>> payload) {
        return payload.onItem()
                .invoke(
                        map -> {
                            try {
                                var str = mapper.writeValueAsString(map);
                                var req =
                                        PutObjectRequest.builder()
                                                .bucket(bucket)
                                                .key(key)
                                                .contentType(HttpMimeType.JSON.mime())
                                                .expires(Instant.now().plus(expiry))
                                                .build();
                                var res = storage.putObject(req, RequestBody.fromString(str));
                                var sc = res.sdkHttpResponse().statusCode();
                                if (!HttpStatusCodeIdentifier.isSuccessCode(sc)) {
                                    throw new CompletionException(
                                            String.format("Bad S3 report storage response: %d", sc),
                                            null);
                                }
                            } catch (JsonProcessingException jpe) {
                                throw new CompletionException(jpe);
                            }
                        });
    }

    private Uni<Map<String, AnalysisResult>> getStorage(String key) {
        return Uni.createFrom()
                .item(
                        () -> {
                            var req = GetObjectRequest.builder().bucket(bucket).key(key).build();
                            try (var res = storage.getObject(req)) {
                                return mapper.readValue(
                                        res, new TypeReference<Map<String, AnalysisResult>>() {});
                            } catch (IOException ioe) {
                                throw new CompletionException(ioe);
                            }
                        });
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording) {
        return reportFor(recording, r -> true);
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(String jvmId, String filename) {
        return reportFor(jvmId, filename, r -> true);
    }

    @Override
    public boolean keyExists(ActiveRecording recording) {
        String key = ReportsService.key(recording);
        return enabled && checkStorage(key).await().atMost(timeout);
    }

    @Override
    public boolean keyExists(String jvmId, String filename) {
        String key = recordingHelper.archivedRecordingKey(jvmId, filename);
        return enabled && checkStorage(key).await().atMost(timeout);
    }
}
