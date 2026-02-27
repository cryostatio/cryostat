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

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

/**
 * Utility class for resolving the current user's username from various sources in order of
 * preference: SecurityContext, SecurityIdentity, X-Forwarded-User header, and remote IP address.
 */
public class UserInfoResolver {

    private static final Logger logger = Logger.getLogger(UserInfoResolver.class);

    private UserInfoResolver() {
        // Utility class
    }

    /**
     * Resolve the current username from available sources. This method is called from
     * SecurityContextFilter with direct access to SecurityContext and RoutingContext.
     *
     * @param securityContext the JAX-RS SecurityContext
     * @param routingContext the Vert.x RoutingContext
     * @return the username, or null if it cannot be determined
     */
    public static String resolveUsername(
            SecurityContext securityContext, RoutingContext routingContext) {
        String username = getUsernameFromSecurityContext(securityContext);
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        username = getUsernameFromSecurityIdentity();
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        username = getUsernameFromForwardedHeader(routingContext);
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        return getRemoteAddressAsUsername(routingContext);
    }

    /**
     * Resolve the current username from available sources. This method is called from contexts
     * where SecurityContext and RoutingContext are not directly available (e.g.,
     * RevisionInfoListener).
     *
     * @return the username, or null if it cannot be determined
     */
    public static String resolveUsername() {
        String username = SecurityContextHolder.getUsername();
        if (StringUtils.isNotBlank(username)) {
            logger.debugf("Retrieved username from ThreadLocal: %s", username);
            return username;
        }

        username = getUsernameFromSecurityContext();
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        username = getUsernameFromSecurityIdentity();
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        username = getUsernameFromForwardedHeader();
        if (StringUtils.isNotBlank(username)) {
            return username;
        }

        return getRemoteAddressAsUsername();
    }

    static String getUsernameFromSecurityContext(SecurityContext securityContext) {
        try {
            if (securityContext != null && securityContext.getUserPrincipal() != null) {
                String username = securityContext.getUserPrincipal().getName();
                if (StringUtils.isNotBlank(username)) {
                    logger.debugf("Retrieved username from SecurityContext: %s", username);
                    return username;
                }
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve username from SecurityContext");
        }
        return null;
    }

    static String getUsernameFromSecurityContext() {
        try {
            CDI<Object> cdi = CDI.current();
            var securityContextInstance = cdi.select(SecurityContext.class);
            if (securityContextInstance.isResolvable()) {
                SecurityContext securityContext = securityContextInstance.get();
                return getUsernameFromSecurityContext(securityContext);
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve username from SecurityContext via CDI");
        }
        return null;
    }

    static String getUsernameFromSecurityIdentity() {
        try {
            CDI<Object> cdi = CDI.current();
            var securityIdentityInstance = cdi.select(SecurityIdentity.class);
            if (securityIdentityInstance.isResolvable()) {
                SecurityIdentity securityIdentity = securityIdentityInstance.get();
                if (securityIdentity != null && !securityIdentity.isAnonymous()) {
                    String username = securityIdentity.getPrincipal().getName();
                    if (StringUtils.isNotBlank(username)) {
                        logger.debugf("Retrieved username from SecurityIdentity: %s", username);
                        return username;
                    }
                }
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve username from SecurityIdentity");
        }
        return null;
    }

    static String getUsernameFromForwardedHeader(RoutingContext routingContext) {
        try {
            if (routingContext != null) {
                String forwardedUser = routingContext.request().getHeader("X-Forwarded-User");
                if (StringUtils.isNotBlank(forwardedUser)) {
                    logger.debugf(
                            "Retrieved username from X-Forwarded-User header: %s", forwardedUser);
                    return forwardedUser;
                }
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve username from X-Forwarded-User header");
        }
        return null;
    }

    static String getRemoteAddressAsUsername(RoutingContext routingContext) {
        try {
            if (routingContext != null) {
                String remoteAddress = getRemoteAddress(routingContext);
                if (StringUtils.isNotBlank(remoteAddress)) {
                    logger.debugf("Using remote address as username: ip:%s", remoteAddress);
                    return "ip:" + remoteAddress;
                }
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve remote address");
        }
        return null;
    }

    static String getUsernameFromForwardedHeader() {
        try {
            CDI<Object> cdi = CDI.current();
            var routingContextInstance = cdi.select(RoutingContext.class);
            if (routingContextInstance.isResolvable()) {
                RoutingContext routingContext = routingContextInstance.get();
                return getUsernameFromForwardedHeader(routingContext);
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve username from X-Forwarded-User header via CDI");
        }
        return null;
    }

    static String getRemoteAddressAsUsername() {
        try {
            CDI<Object> cdi = CDI.current();
            var routingContextInstance = cdi.select(RoutingContext.class);
            if (routingContextInstance.isResolvable()) {
                RoutingContext routingContext = routingContextInstance.get();
                return getRemoteAddressAsUsername(routingContext);
            }
        } catch (Exception e) {
            logger.debugf(e, "Could not retrieve remote address via CDI");
        }
        return null;
    }

    static String getRemoteAddress(RoutingContext routingContext) {
        String forwardedFor = routingContext.request().getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwardedFor)) {
            // X-Forwarded-For can contain multiple IPs, take the first one (original client)
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex > 0
                    ? forwardedFor.substring(0, commaIndex).trim()
                    : forwardedFor.trim();
        }

        String realIp = routingContext.request().getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(realIp)) {
            return realIp.trim();
        }

        if (routingContext.request().remoteAddress() != null) {
            return routingContext.request().remoteAddress().host();
        }

        return null;
    }
}
