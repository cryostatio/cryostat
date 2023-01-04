/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.security.auth;

import java.security.Permission;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.inject.Alternative;
import javax.inject.Singleton;

import io.quarkus.arc.Priority;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Alternative
@Priority(10)
@IfBuildProfile("dev")
public class DevBasicIdentityProvider
        implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
        var username = request.getUsername();
        if (!"user".equals(username)) {
            return Uni.createFrom().failure(new UnauthorizedException());
        }
        var pass = request.getPassword();
        if (!Arrays.equals(new char[] {'p', 'a', 's', 's'}, pass.getPassword())) {
            return Uni.createFrom().failure(new UnauthorizedException());
        }
        var user =
                new SecurityIdentity() {

                    @Override
                    public Principal getPrincipal() {
                        return new Principal() {
                            @Override
                            public String getName() {
                                return username;
                            }
                        };
                    }

                    @Override
                    public boolean isAnonymous() {
                        return false;
                    }

                    @Override
                    public Set<String> getRoles() {
                        // TODO extract these
                        var actions = List.of("create", "read", "update", "delete");
                        var resources =
                                List.of("target", "recording", "report", "template", "rule", "credential");
                        var roles = new HashSet<String>();
                        for (var action : actions) {
                            for (var resource : resources) {
                                roles.add(resource + ":" + action);
                            }
                        }
                        return roles;
                    }

                    @Override
                    public boolean hasRole(String role) {
                        return getRoles().contains(role);
                    }

                    @Override
                    public <T extends Credential> T getCredential(Class<T> credentialType) {
                        return null;
                    }

                    @Override
                    public Set<Credential> getCredentials() {
                        return Set.of();
                    }

                    @Override
                    public <T> T getAttribute(String name) {
                        return (T) getAttributes().get(name);
                    }

                    @Override
                    public Map<String, Object> getAttributes() {
                        return Map.of();
                    }

                    @Override
                    public Uni<Boolean> checkPermission(Permission permission) {
                        return Uni.createFrom().item(true);
                    }
                };
        return Uni.createFrom().item(user);
    }
}
