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
package io.cryostat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JsonRequestFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (requestContext.getMediaType() != null
                && requestContext
                        .getMediaType()
                        .isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)) {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(requestContext.getEntityStream()));
            try {
                String line = reader.readLine();
                if (line.contains("\"id\"")) {
                    requestContext.abortWith(
                            Response.status(Response.Status.BAD_REQUEST)
                                    .entity("ID field cannot be specified in the request body")
                                    .build());
                }
            } finally {
                reader.close();
            }
        }
    }
}
