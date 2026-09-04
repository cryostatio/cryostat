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

import io.cryostat.targets.Target;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v4.3/targets/{targetId}/recordings_sync")
public class RecordingSync {

    @Inject RecordingHelper recordingHelper;

    /**
     * Synchronizes Cryostat's active recording model with the specified target.
     *
     * @param targetId the database identifier of the target to synchronize
     */
    @POST
    @Blocking
    @Transactional
    @RolesAllowed("write")
    @Operation(
            summary = "Resynchronize active recordings on the specified target",
            description =
                    "Reconcile Cryostat's active recording model with the recordings present on the"
                            + " target.")
    @APIResponse(responseCode = "204", description = "Active recordings synchronized")
    public void sync(@RestPath long targetId) {
        recordingHelper.syncActiveRecordings(Target.getTargetById(targetId));
    }
}
