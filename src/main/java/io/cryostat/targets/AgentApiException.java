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
package io.cryostat.targets;

import jakarta.ws.rs.WebApplicationException;

/**
 * Indicates an HTTP exception during regular communications with a Cryostat Agent instance.
 *
 * @see io.cryostat.target.AgentClient
 */
public class AgentApiException extends WebApplicationException {
    public AgentApiException(int statusCode) {
        super(String.format("Unexpected HTTP response code %d", statusCode));
    }
}
