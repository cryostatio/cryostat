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

import io.cryostat.Producers;
import io.cryostat.util.HttpMimeType;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.Identifier;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/activedownload/{id}")
public class ActiveRecordingsDownload {

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @GET
    @Blocking
    @RolesAllowed("read")
    @Operation(
            summary = "Download a Flight Recording binary file",
            description =
                    """
                    Given a recording ID and a remote recording ID within that target, Cryostat will open a remote
                    connection to the target and pipe back a data stream containing the Flight Recording binary file
                    format for that recording. The client can feed this data to other tooling which ingests the JFR
                    binary file format.
                    """)
    public RestResponse<InputStream> handleActiveDownload(@RestPath long id) throws Exception {
        ActiveRecording recording = ActiveRecording.find("id", id).singleResult();
        return ResponseBuilder.<InputStream>ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s.jfr\"", recording.name))
                .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                .entity(recordingHelper.getActiveInputStream(recording))
                .build();
    }
}
