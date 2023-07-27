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
package io.cryostat.security.auth;

import java.util.Set;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.BasicAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import org.jboss.logging.Logger;

@ApplicationScoped
@Alternative
@Priority(0)
public class CryostatWebAuthMechanism implements HttpAuthenticationMechanism {

    @Inject Logger logger;
    // TODO replace this with an OAuth mechanism full-time
    @Inject BasicAuthenticationMechanism delegate;

    @Override
    public Uni<SecurityIdentity> authenticate(
            RoutingContext context, IdentityProviderManager identityProviderManager) {
        return delegate.authenticate(context, identityProviderManager);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        int statusCode = HttpResponseStatus.UNAUTHORIZED.code();
        // prepend the 'X-' to the header name so the web-client JS can read it and the browser does
        // not intervene as it normally does to this header
        String headerName = "X-" + HttpHeaders.WWW_AUTHENTICATE;
        // FIXME the content should not need to be capitalized, but the web-client currently
        // requires this
        String content = "Basic";
        var cd = new ChallengeData(statusCode, headerName, content);
        return Uni.createFrom().item(cd);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return delegate.getCredentialTypes();
    }
}
