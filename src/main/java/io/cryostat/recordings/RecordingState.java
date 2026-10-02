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
package io.cryostat.recordings;

import org.eclipse.microprofile.graphql.Description;

/**
 * The running state of a Flight Recording on a remote target JVM.
 *
 * <p>This mirrors {@code jdk.jfr.RecordingState}, which Cryostat previously used directly even
 * though it never holds a local {@code jdk.jfr.Recording} - remote recording state always arrives
 * as an {@code IRecordingDescriptor.RecordingState} and is mapped across. Owning the enum lets
 * Jandex index it, which is what the GraphQL schema scanner needs in order to emit the {@code
 * RecordingState} type; a JDK class cannot be indexed from a dependency jar.
 *
 * <p><b>The declaration order is load-bearing.</b> {@link ActiveRecording#state} is persisted with
 * the default {@code EnumType.ORDINAL} mapping against a {@code smallint check (state between 0 and
 * 4)} column, so these constants must stay in this order, and new ones may only be appended.
 */
@Description("Running state of an active Flight Recording")
public enum RecordingState {
    NEW,
    DELAYED,
    RUNNING,
    STOPPED,
    CLOSED,
    ;
}
