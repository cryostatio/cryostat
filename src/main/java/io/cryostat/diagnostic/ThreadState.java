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

/**
 * The state of a thread in a captured thread dump.
 *
 * <p>This mirrors {@link java.lang.Thread.State}. Owning the enum lets Jandex index it, which is
 * what the OpenAPI schema scanner needs in order to emit it as a string enum; a JDK class cannot be
 * indexed from a dependency jar, so the scanner would otherwise fall back to documenting it as an
 * untyped object.
 */
public enum ThreadState {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED,
    ;

    /** VM-internal threads have no state, so a null input maps to a null result. */
    public static ThreadState from(Thread.State state) {
        return state == null ? null : valueOf(state.name());
    }
}
