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

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v5/targets/{jvmId}/diagnostics/gc")
public class GarbageCollectionEndpoint {

    @Inject TargetConnectionManager targetConnectionManager;

    @PermissionsAllowed(
            value = {"targets:read", "diagnostics:write"},
            inclusive = true)
    @Blocking
    @POST
    @Operation(
            summary = "Initiate a garbage collection on the specified target",
            description =
                    """
                    Request the remote target to perform a garbage collection. The target JVM is free to ignore this
                    request. This is generally equivalent to a System.gc() call made within the target JVM.
                    """)
    public void gc(@RestPath String jvmId) {
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
        GarbageCollection gc =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    GarbageCollection entity = GarbageCollection.of(target);
                                    entity.persist();
                                    return entity;
                                });
        targetConnectionManager.executeConnectedTask(
                target,
                conn ->
                        conn.invokeMBeanOperation(
                                "java.lang:type=Memory", "gc", null, null, Void.class));
        QuarkusTransaction.requiringNew().run(() -> GarbageCollection.deleteById(gc.id));
    }
}
