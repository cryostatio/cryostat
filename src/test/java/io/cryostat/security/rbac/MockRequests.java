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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.RoutingContext;

/**
 * Builds mock {@link RoutingContext}s whose {@code getHeader} reads through to a real {@link
 * HeadersMultiMap}, so that header mutation performed by the code under test (e.g. stripping the
 * agent provenance stamp) is visible to subsequent reads, as it would be on a real request.
 */
final class MockRequests {

    private MockRequests() {}

    static RoutingContext context(String... headerNameValuePairs) {
        if (headerNameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of name/value arguments");
        }
        var headers = HeadersMultiMap.headers();
        for (int i = 0; i < headerNameValuePairs.length; i += 2) {
            headers.add(headerNameValuePairs[i], headerNameValuePairs[i + 1]);
        }
        return context(headers);
    }

    static RoutingContext context(HeadersMultiMap headers) {
        var ctx = mock(RoutingContext.class);
        var req = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.headers()).thenReturn(headers);
        when(req.getHeader(anyString())).thenAnswer(inv -> headers.get(inv.<String>getArgument(0)));
        return ctx;
    }
}
