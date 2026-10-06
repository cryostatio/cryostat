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

import java.util.List;
import java.util.Objects;

import io.cryostat.recordings.ActiveRecordings.Metadata;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Diagnostics {

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedHeapDumpDirectory(String jvmId, List<HeapDump> heapDumps) {
        public ArchivedHeapDumpDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(heapDumps);
        }
    }

    public record HeapDump(
            String jvmId,
            String downloadUrl,
            String heapDumpId,
            long lastModified,
            long size,
            Metadata metadata) {

        public HeapDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(heapDumpId);
            Objects.requireNonNull(metadata);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedThreadDumpDirectory(String jvmId, List<ThreadDump> threadDumps) {
        public ArchivedThreadDumpDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(threadDumps);
        }
    }

    public record ThreadDump(
            String jvmId,
            String downloadUrl,
            String threadDumpId,
            long lastModified,
            long size,
            Metadata metadata) {
        public ThreadDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(threadDumpId);
            Objects.requireNonNull(metadata);
        }
    }
}
