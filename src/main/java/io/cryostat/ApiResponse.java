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

import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

public class ApiResponse {

    public static Response success(Object payload) {
        return Response.ok(payload).type(MediaType.APPLICATION_JSON).build();
    }

    public static Response error(Response.Status status, String message) {
        ErrorResponse errorResponse = new ErrorResponse(message, status.getStatusCode());
        return Response.status(status)
                .entity(errorResponse)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static class ErrorResponse {
        private String message;
        private int statusCode;

        public ErrorResponse(String message, int statusCode) {
            this.message = message;
            this.statusCode = statusCode;
        }

        public String getMessage() {
            return message;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public static Object json(Status ok, Map<String, String> of) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'json'");
    }
}
