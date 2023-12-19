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

import java.util.Objects;

import jakarta.annotation.Nullable;

public record V2Response(Meta meta, Data data) {
    public static V2Response json(Object payload, String status) {
        return new V2Response(new Meta("application/json", status), new Data(payload));
    }

    // FIXME the type and status should both come from an enum and be non-null
    public record Meta(String type, String status) {
        public Meta {
            Objects.requireNonNull(type);
            Objects.requireNonNull(status);
        }
    }

    public record Data(@Nullable Object result) {}
}
