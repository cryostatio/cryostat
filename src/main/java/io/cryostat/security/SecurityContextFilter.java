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
package io.cryostat.security;

import java.io.IOException;

import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * Request filter that resolves and stores the current username in a ThreadLocal for access by
 * components that cannot directly inject security context, such as Hibernate Envers
 * RevisionListener implementations.
 */
@Provider
@Priority(1000)
public class SecurityContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger logger = Logger.getLogger(SecurityContextFilter.class);

    @Context SecurityContext securityContext;

    @Inject RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String username = UserInfoResolver.resolveUsername(securityContext, routingContext);
        if (StringUtils.isNotBlank(username)) {
            SecurityContextHolder.setUsername(username);
            logger.debugf("Stored username in ThreadLocal: %s", username);
        }
    }

    @Override
    public void filter(
            ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        SecurityContextHolder.clearUsername();
        logger.debugf("Cleared username from ThreadLocal");
    }
}
