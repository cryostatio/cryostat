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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JsonRequestFilterTest {
    private JsonRequestFilter filter;
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() {
        filter = new JsonRequestFilter();
        requestContext = mock(ContainerRequestContext.class);
    }

    @Test
    void testRejectsPayloadWithId() throws Exception {

        String[] testPayloads = {
            "{\"id\": 1}",
            "{\n  \"id\": 1\n}",
            "{\n  \"foo\": \"bar\",\n  \"id\": 1\n}",
            "[\n  {\n    \"id\": 1\n  }\n]"
        };

        for (String payload : testPayloads) {
            simulateRequest(payload);
            verify(requestContext, times(1)).abortWith(any(Response.class));
            reset(requestContext);
        }
    }

    @Test
    void testAllowsPayloadWithoutId() throws Exception {

        String[] testPayloads = {
            "{ \"message\": \"this text includes the string literal \\\"id\\\"\" }",
            "{}",
            "[]",
            "{ \"foo\": \"bar\" }"
        };

        for (String payload : testPayloads) {
            simulateRequest(payload);
            verify(requestContext, never()).abortWith(any(Response.class));
            reset(requestContext);
        }
    }

    private void simulateRequest(String jsonPayload) throws Exception {

        ByteArrayInputStream payloadStream =
                new ByteArrayInputStream(jsonPayload.getBytes(StandardCharsets.UTF_8));
        when(requestContext.getEntityStream()).thenReturn(payloadStream);
        when(requestContext.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        filter.filter(requestContext);
    }
}
