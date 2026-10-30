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
package io.cryostat.diagnostics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.diagnostic.Diagnostics.HeapDump;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.TestFileUpload;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class DiagnosticsHelperTest extends AbstractTransactionalTestBase {

    @Inject DiagnosticsHelper helper;

    @Test
    void shouldPreserveSlashesInFilenameWhenListingHeapDumps() throws Exception {
        String jvmId = "slash-heap-jvm-id";
        String filename = "dir/sub/slash-heap.hprof";
        Path file = Files.createTempFile("diagnostics-helper-slash", ".hprof");
        Files.write(file, new byte[] {1, 2, 3, 4});

        try {
            helper.addHeapDump(
                    jvmId,
                    new TestFileUpload(filename, file),
                    "request-id",
                    new Metadata(Map.of("purpose", "slash-filename")));

            var byJvm = helper.getHeapDumps(jvmId);
            assertThat(byJvm, hasSize(1));
            HeapDump dump = byJvm.get(0);
            assertThat(dump.jvmId(), is(jvmId));
            assertThat(dump.heapDumpId(), is(filename));
            assertThat(dump.downloadUrl(), is(helper.heapDumpDownloadUrl(jvmId, filename)));
            assertThat(dump.metadata().labels().get("purpose"), is("slash-filename"));

            assertThat(
                    helper.decodedKey(helper.encodedKey(jvmId, filename)),
                    is(Pair.of(jvmId, filename)));
        } finally {
            helper.deleteHeapDump(jvmId, filename);
            Files.deleteIfExists(file);
        }
    }
}
