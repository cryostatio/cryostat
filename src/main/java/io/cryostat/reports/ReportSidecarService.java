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
import java.util.Map;

import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;

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
public interface ReportSidecarService {
    @Path("/report")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Uni<Map<String, AnalysisResult>> generate(
            @RestForm("file") @PartType(MediaType.APPLICATION_OCTET_STREAM) InputStream file,
            @RestForm("filter") @PartType(MediaType.TEXT_PLAIN) String filter);

    @Path("/remote_report")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Uni<Map<String, AnalysisResult>> generatePresigned(
            @RestForm("path") @PartType(MediaType.TEXT_PLAIN) String path,
            @RestForm("query") @PartType(MediaType.TEXT_PLAIN) String query,
            @RestForm("filter") @PartType(MediaType.TEXT_PLAIN) String filter);
}
