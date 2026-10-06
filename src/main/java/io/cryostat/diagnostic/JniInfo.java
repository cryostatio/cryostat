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
package io.cryostat.diagnostic;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JNI reference counts reported by a thread dump.
 *
 * <p>Projected from {@link me.bechberger.jthreaddump.model.JniInfo} so that the parser's model does
 * not appear in the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JniInfo(
        Integer globalRefs, Integer weakRefs, Long globalRefsMemory, Long weakRefsMemory) {

    public static JniInfo from(me.bechberger.jthreaddump.model.JniInfo info) {
        return info == null
                ? null
                : new JniInfo(
                        info.globalRefs(),
                        info.weakRefs(),
                        info.globalRefsMemory(),
                        info.weakRefsMemory());
    }
}
