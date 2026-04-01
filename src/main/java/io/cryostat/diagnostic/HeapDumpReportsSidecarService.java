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

import java.io.InputStream;

import io.cryostat.core.diagnostic.HeapDumpAnalysis;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

/**
 * Interface for HTTP client to communicate with reports sidecar generators instances. See
 * https://github.com/cryostatio/cryostat-reports
 */
@RegisterRestClient(configKey = "reports")
@ApplicationScoped
public interface HeapDumpReportsSidecarService {
    @Path("/heapdump/report")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Uni<HeapDumpAnalysis> generate(
            @RestForm("file") @PartType(MediaType.APPLICATION_OCTET_STREAM) InputStream file,
            @RestForm("jvmId") @PartType(MediaType.TEXT_PLAIN) String jvmId,
            @RestForm("heapDumpID") @PartType(MediaType.TEXT_PLAIN) String heapDumpId);

    @Path("/heapdump/remote_report")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Uni<HeapDumpAnalysis> generatePresigned(
            @RestForm("uri") @PartType(MediaType.TEXT_PLAIN) String uri,
            @RestForm("jvmId") @PartType(MediaType.TEXT_PLAIN) String jvmId,
            @RestForm("heapDumpID") @PartType(MediaType.TEXT_PLAIN) String heapDumpId);
}
