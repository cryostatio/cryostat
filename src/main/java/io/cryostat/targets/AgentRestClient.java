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

package io.cryostat.targets;

import java.io.InputStream;
import java.util.Map;

import io.cryostat.targets.AgentJFRService.StartRecordingRequest;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(
        configKey = "agents",
        // baseUri should not be used - we always use an overridden URL for every request
        baseUri = "http://localhost")
interface AgentRestClient {
    @Path("/")
    @GET
    Uni<Response> ping();

    @Path("/mbean-metrics/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> getMbeanMetrics();

    @Path("/mbean-invoke/")
    @POST
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    Uni<Response> invokeMBeanOperation(InputStream payload);

    @Path("/smart-triggers/")
    @POST
    Uni<Response> addTriggers(String definitions);

    @Path("/smart-triggers/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> listTriggers();

    @Path("/smart-triggers/")
    @DELETE
    Uni<Response> removeTriggers(String definitions);

    @Path("/recordings/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> listRecordings();

    @Path("/recordings/")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<Response> startRecording(StartRecordingRequest payload);

    @Path("/recordings/{id}")
    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<Response> updateRecordingOptions(
            @PathParam("id") long recordingId, Map<String, Object> settings);

    @Path("/recordings/{id}")
    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    Uni<Response> openStream(@PathParam("id") long id);

    @Path("/recordings/{id}")
    @DELETE
    Uni<Response> deleteRecording(@PathParam("id") long id);

    @Path("/event-types/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> listEventTypes();

    @Path("/event-settings/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> listEventSettings();

    @Path("/event-templates/")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Response> listEventTemplates();
}
