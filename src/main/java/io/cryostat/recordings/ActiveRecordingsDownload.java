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

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.util.HttpMimeType;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/activedownload/{id}")
public class ActiveRecordingsDownload {

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @ConfigProperty(name = ConfigProperties.STORAGE_TRANSIENT_ARCHIVES_ENABLED)
    boolean transientArchivesEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_TRANSIENT_ARCHIVES_TTL)
    Duration transientArchivesTtl;

    @GET
    @Blocking
    @RolesAllowed("read")
    public RestResponse<InputStream> handleActiveDownload(@RestPath long id) throws Exception {
        ActiveRecording recording = ActiveRecording.find("id", id).singleResult();
        if (!transientArchivesEnabled) {
            return ResponseBuilder.<InputStream>ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s.jfr\"", recording.name))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(
                            recordingHelper.getActiveInputStream(
                                    recording, connectionFailedTimeout))
                    .build();
        }

        String savename = recording.name;
        String filename =
                recordingHelper
                        .archiveRecording(
                                recording, savename, Instant.now().plus(transientArchivesTtl))
                        .name();
        String encodedKey = recordingHelper.encodedKey(recording.target.jvmId, filename);
        if (!savename.endsWith(".jfr")) {
            savename += ".jfr";
        }
        return ResponseBuilder.<InputStream>create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", savename))
                .location(
                        URI.create(
                                String.format(
                                        "/api/v4/download/%s?f=%s",
                                        encodedKey,
                                        base64Url.encodeAsString(
                                                savename.getBytes(StandardCharsets.UTF_8)))))
                .build();
    }
}
