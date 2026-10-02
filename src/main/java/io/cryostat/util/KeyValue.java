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

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A single entry of a string-to-string map as it appears in API <i>responses</i>. {@link
 * io.cryostat.ObjectMapperCustomization.MapSerializer} serializes every {@code Map<String, String>}
 * as an array of these objects rather than as a JSON object, so response schemas must be annotated
 * with {@code @Schema(type = SchemaType.ARRAY, implementation = KeyValue.class)} to match. Request
 * bodies are unaffected - deserialization still expects the plain JSON object form.
 *
 * <p>This type exists only to describe that shape in the generated OpenAPI document; no Cryostat
 * code serializes or deserializes it.
 */
@Schema(
        name = "KeyValue",
        description =
                """
                A single entry of a string-to-string map. Cryostat emits such maps as arrays of
                these objects, but accepts them in request bodies as plain JSON objects.
                """)
public record KeyValue(String key, String value) {}
