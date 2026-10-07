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
package io.cryostat.security.rbac;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * JAX-RS {@link ContainerResponseFilter} that post-filters response collections for endpoints
 * annotated with {@link AuthorizationFiltered}.
 *
 * <p>For each item in the response collection, this filter:
 *
 * <ol>
 *   <li>Extracts the jvmId using the accessor declared in the annotation.
 *   <li>Resolves the Kubernetes namespace via {@link SecurityContextResolver}.
 *   <li>Checks each declared permission via {@link UserAuthorizer#isAuthorized(String, String,
 *       String, String)}.
 *   <li>Retains the item only if all checks pass.
 * </ol>
 *
 * <p>The filter is a no-op in {@link RbacMode#PERMISSIVE} and {@link RbacMode#BASIC} modes, because
 * {@link UserAuthorizer#isAuthorized} short-circuits to {@code true} in those modes.
 */
@Provider
public class AuthorizationFilteredResponseFilter implements ContainerResponseFilter {

    @Context ResourceInfo resourceInfo;

    @Inject UserAuthorizer userAuthorizer;
    @Inject SecurityContextResolver securityContextResolver;
    @Inject RoutingContext routingContext;
    @Inject Logger logger;

    @Override
    public void filter(
            ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        logger.debugf(
                "AuthorizationFilteredResponseFilter: entered for %s %s (status %d)",
                requestContext.getMethod(),
                requestContext.getUriInfo().getRequestUri(),
                responseContext.getStatus());
        var method = resourceInfo.getResourceMethod();
        if (method == null) {
            logger.debug(
                    "AuthorizationFilteredResponseFilter: resourceInfo.getResourceMethod() is"
                            + " null, skipping");
            return;
        }
        AuthorizationFiltered annotation = method.getAnnotation(AuthorizationFiltered.class);
        if (annotation == null) {
            logger.debugf(
                    "AuthorizationFilteredResponseFilter: no @AuthorizationFiltered on %s.%s,"
                            + " skipping",
                    method.getDeclaringClass().getSimpleName(), method.getName());
            return;
        }
        logger.debugf(
                "AuthorizationFilteredResponseFilter: @AuthorizationFiltered found on %s.%s"
                        + " (permissions=%s)",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                String.join(",", annotation.permissions()));
        String rawToken = (String) routingContext.get(SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN);
        if (StringUtils.isBlank(rawToken)) {
            logger.debugf(
                    "AuthorizationFilteredResponseFilter: raw access token is blank on"
                            + " RoutingContext attribute '%s', skipping — response will NOT be"
                            + " filtered",
                    SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN);
            return;
        }
        Object entity = responseContext.getEntity();
        if (!(entity instanceof Collection)) {
            logger.debugf(
                    "AuthorizationFilteredResponseFilter: response entity is %s, not a"
                            + " Collection, skipping",
                    entity == null ? "null" : entity.getClass().getName());
            return;
        }
        Collection<?> items = (Collection<?>) entity;
        int beforeCount = items.size();
        List<Object> filtered = new ArrayList<>(beforeCount);
        for (Object item : items) {
            String jvmId = extractJvmId(item, annotation);
            String namespace = securityContextResolver.resolveNamespace(jvmId);
            boolean granted = true;
            for (String permission : annotation.permissions()) {
                int colon = permission.indexOf(':');
                if (colon < 0) {
                    logger.warnf(
                            "AuthorizationFilteredResponseFilter: malformed permission '%s',"
                                    + " skipping",
                            permission);
                    continue;
                }
                String resource = permission.substring(0, colon);
                String verb = permission.substring(colon + 1);
                if (!userAuthorizer.isAuthorized(resource, verb, namespace, rawToken)) {
                    logger.debugf(
                            "AuthorizationFilteredResponseFilter: DENIED %s:%s in namespace '%s'"
                                    + " for jvmId '%s'",
                            resource, verb, namespace, jvmId);
                    granted = false;
                    break;
                }
            }
            if (granted) {
                logger.debugf(
                        "AuthorizationFilteredResponseFilter: ALLOWED jvmId '%s' in namespace"
                                + " '%s'",
                        jvmId, namespace);
                filtered.add(item);
            }
        }
        logger.debugf(
                "AuthorizationFilteredResponseFilter: %s filtered %d → %d items",
                method.getName(), beforeCount, filtered.size());
        responseContext.setEntity(filtered);
    }

    private String extractJvmId(Object item, AuthorizationFiltered annotation) {
        String name = annotation.jvmIdAccessorName();
        try {
            if (annotation.jvmIdAccessorType() == AccessorType.FIELD) {
                Field field = item.getClass().getField(name);
                Object value = field.get(item);
                return value != null ? value.toString() : null;
            } else {
                Method accessor = item.getClass().getMethod(name);
                Object value = accessor.invoke(item);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            logger.warnf(
                    e,
                    "AuthorizationFilteredResponseFilter: failed to extract jvmId via %s '%s' on"
                            + " %s",
                    annotation.jvmIdAccessorType(),
                    name,
                    item.getClass().getSimpleName());
            return null;
        }
    }
}
