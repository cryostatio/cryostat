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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.Principal;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserInfoResolverTest {

    @Mock SecurityContext securityContext;

    @Mock RoutingContext routingContext;

    @Mock HttpServerRequest httpServerRequest;

    @Mock Principal principal;

    @Mock SocketAddress socketAddress;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearUsername();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearUsername();
    }

    @Test
    void testGetUsernameFromSecurityContext() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");

        String username = UserInfoResolver.getUsernameFromSecurityContext(securityContext);

        assertEquals("testuser", username);
    }

    @Test
    void testGetUsernameFromSecurityContextWithNullPrincipal() {
        when(securityContext.getUserPrincipal()).thenReturn(null);

        String username = UserInfoResolver.getUsernameFromSecurityContext(securityContext);

        assertNull(username);
    }

    @Test
    void testGetUsernameFromSecurityContextWithNullContext() {
        String username = UserInfoResolver.getUsernameFromSecurityContext(null);

        assertNull(username);
    }

    @Test
    void testGetUsernameFromForwardedHeader() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-User")).thenReturn("forwardeduser");

        String username = UserInfoResolver.getUsernameFromForwardedHeader(routingContext);

        assertEquals("forwardeduser", username);
    }

    @Test
    void testGetUsernameFromForwardedHeaderWithNullContext() {
        String username = UserInfoResolver.getUsernameFromForwardedHeader(null);

        assertNull(username);
    }

    @Test
    void testGetUsernameFromForwardedHeaderWithNoHeader() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-User")).thenReturn(null);

        String username = UserInfoResolver.getUsernameFromForwardedHeader(routingContext);

        assertNull(username);
    }

    @Test
    void testGetRemoteAddressAsUsernameFromXForwardedFor() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");

        String username = UserInfoResolver.getRemoteAddressAsUsername(routingContext);

        assertEquals("ip:192.168.1.100", username);
    }

    @Test
    void testGetRemoteAddressAsUsernameFromXForwardedForMultiple() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-For"))
                .thenReturn("192.168.1.100, 10.0.0.1, 172.16.0.1");

        String username = UserInfoResolver.getRemoteAddressAsUsername(routingContext);

        assertEquals("ip:192.168.1.100", username);
    }

    @Test
    void testGetRemoteAddressAsUsernameFromXRealIP() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpServerRequest.getHeader("X-Real-IP")).thenReturn("192.168.1.200");

        String username = UserInfoResolver.getRemoteAddressAsUsername(routingContext);

        assertEquals("ip:192.168.1.200", username);
    }

    @Test
    void testGetRemoteAddressAsUsernameFromSocketAddress() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpServerRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpServerRequest.remoteAddress()).thenReturn(socketAddress);
        when(socketAddress.host()).thenReturn("192.168.1.50");

        String username = UserInfoResolver.getRemoteAddressAsUsername(routingContext);

        assertEquals("ip:192.168.1.50", username);
    }

    @Test
    void testGetRemoteAddressAsUsernameWithNullContext() {
        String username = UserInfoResolver.getRemoteAddressAsUsername(null);

        assertNull(username);
    }

    @Test
    void testGetRemoteAddress() {
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");

        String address = UserInfoResolver.getRemoteAddress(routingContext);

        assertEquals("10.0.0.1", address);
    }

    @Test
    void testResolveUsernameWithDirectParameters() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("directuser");

        String username = UserInfoResolver.resolveUsername(securityContext, routingContext);

        assertEquals("directuser", username);
    }

    @Test
    void testResolveUsernameWithDirectParametersFallbackToForwardedHeader() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-User")).thenReturn("headeruser");

        String username = UserInfoResolver.resolveUsername(securityContext, routingContext);

        assertEquals("headeruser", username);
    }

    @Test
    void testResolveUsernameWithDirectParametersFallbackToRemoteAddress() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(routingContext.request()).thenReturn(httpServerRequest);
        when(httpServerRequest.getHeader("X-Forwarded-User")).thenReturn(null);
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("10.0.0.5");

        String username = UserInfoResolver.resolveUsername(securityContext, routingContext);

        assertEquals("ip:10.0.0.5", username);
    }

    @Test
    void testResolveUsernameFromThreadLocal() {
        SecurityContextHolder.setUsername("threadlocaluser");

        String username = UserInfoResolver.resolveUsername();

        assertEquals("threadlocaluser", username);
    }
}
