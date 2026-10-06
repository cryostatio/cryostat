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
package io.cryostat.diagnostic;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.util.HttpMimeType;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("/api/v5/diagnostics/unified-logs/download/{encodedKey}")
public class UnifiedLogDownload {

    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject DiagnosticsHelper helper;
    @Inject Logger log;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_UNIFIED_LOGS)
    String logsBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @PermissionsAllowed(value = "unifiedlogs:read", inclusive = true)
    @Blocking
    @GET
    public RestResponse<Object> handleUnifiedLogStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.tracev("Handling log download Request for key: {0}", decodedKey);
        String key = helper.storageKey(decodedKey);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(logsBucket).key(key).build())
                    .sdkHttpResponse();
        } catch (NoSuchKeyException e) {
            log.warnv("Failed to find log for key {0}", decodedKey.toString());
            throw new NotFoundException(e);
        }
        String contentName = StringUtils.isNotBlank(filename) ? filename : decodedKey.getRight();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", contentName))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getUnifiedLogStream(encodedKey))
                    .build();
        }

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(logsBucket).key(key).build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URI uri = presignedRequest.url().toURI();
        if (externalStorageUrl.isPresent()) {
            String extUrl = externalStorageUrl.orElseThrow();
            if (StringUtils.isNotBlank(extUrl)) {
                URI extUri = new URI(extUrl);
                uri =
                        new URI(
                                extUri.getScheme(),
                                extUri.getAuthority(),
                                URI.create(String.format("%s/%s", extUri.getPath(), uri.getPath()))
                                        .normalize()
                                        .getPath(),
                                uri.getQuery(),
                                uri.getFragment());
            }
        }
        return ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", contentName))
                .location(uri)
                .build();
    }
}
