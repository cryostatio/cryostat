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
package io.cryostat.audit;

import java.io.IOException;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(2000)
public class AuditCacheControlFilter implements ContainerResponseFilter {

    private static final String AUDIT_PATH_PREFIX = "/api/beta/audit/";

    @Override
    public void filter(
            ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        String path = requestContext.getUriInfo().getPath();
        if (path != null && path.startsWith(AUDIT_PATH_PREFIX)) {
            responseContext
                    .getHeaders()
                    .putSingle("Cache-Control", "no-cache, no-store, must-revalidate");
            responseContext.getHeaders().putSingle("Pragma", "no-cache");
            responseContext.getHeaders().putSingle("Expires", "0");
        }
    }
}
