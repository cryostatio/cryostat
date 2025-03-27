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

import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.RecordingHelper.SnapshotCreationException;
import io.cryostat.targets.Target;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/targets/{targetId}/snapshot")
public class Snapshots {

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @POST
    @Transactional
    @RolesAllowed("write")
    public Uni<RestResponse<LinkedRecordingDescriptor>> createSnapshotUsingTargetId(
            @RestPath long targetId) throws Exception {
        return Uni.createFrom()
                .item(recordingHelper.createSnapshot(Target.find("id", targetId).singleResult()))
                .onItem()
                .transform(
                        recording ->
                                ResponseBuilder.ok(recordingHelper.toExternalForm(recording))
                                        .build())
                .onFailure(SnapshotCreationException.class)
                .recoverWithItem(ResponseBuilder.<LinkedRecordingDescriptor>accepted().build());
    }
}
