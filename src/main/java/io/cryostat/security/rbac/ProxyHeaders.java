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

/**
 * Names of the HTTP headers applied by the reverse proxies in front of Cryostat.
 *
 * <p>Declared in one place so that {@link RequestProvenance}, which decides which proxy a request
 * came from, and {@link RbacHttpAuthenticationMechanism}, which reads the identity those proxies
 * forward, cannot disagree about a header name.
 *
 * <p>Two of these are not alike in kind. {@link #AGENT_AUTH} and {@link #USER_PROXY_AUTH} are the
 * only headers whose <em>value</em> is a trust decision: each is a shared secret applied by exactly
 * one proxy hop and cleared by every other. The rest are payload, meaningful only on a request
 * whose provenance one of those two stamps has already established.
 */
final class ProxyHeaders {

    private ProxyHeaders() {}

    /** Shared secret proving that the agent gateway forwarded this request. */
    static final String AGENT_AUTH = "X-Cryostat-Agent-Auth";

    /** Shared secret proving that the user-path auth-strip proxy forwarded this request. */
    static final String USER_PROXY_AUTH = "X-Cryostat-User-Proxy-Auth";

    /** Authenticated username, applied by oauth-proxy and re-sourced by the auth-strip proxy. */
    static final String FORWARDED_USER = "X-Forwarded-User";

    /** Caller's access token, applied by oauth-proxy for browser sessions. */
    static final String FORWARDED_TOKEN = "X-Forwarded-Access-Token";

    /** Bearer token, used by programmatic API clients in place of {@link #FORWARDED_TOKEN}. */
    static final String AUTHORIZATION = "Authorization";
}
