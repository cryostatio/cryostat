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
package io.cryostat.util;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class EntityExistsException extends ClientErrorException {
    public EntityExistsException(String type, String name) {
        super(
                String.format(
                        "%s with name %s already exists. Try again with a different name",
                        type, name),
                Response.Status.CONFLICT);
    }
}
