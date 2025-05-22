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

import java.io.IOException;
import java.util.Optional;

import io.cryostat.recordings.ActiveRecordings.Metadata;

import com.fasterxml.jackson.core.JsonProcessingException;

public interface ArchivedRecordingMetadataService {
    default void create(String jvmId, String filename, Metadata metadata)
            throws JsonProcessingException {
        create(RecordingHelper.archivedRecordingKey(jvmId, filename), metadata);
    }

    void create(String storageKey, Metadata metadata) throws JsonProcessingException;

    default Optional<Metadata> read(String jvmId, String filename) throws IOException {
        return read(RecordingHelper.archivedRecordingKey(jvmId, filename));
    }

    Optional<Metadata> read(String storageKey) throws IOException;

    default void update(String storageKey, Metadata metadata) throws JsonProcessingException {
        delete(storageKey);
        create(storageKey, metadata);
    }

    default void update(String jvmId, String filename, Metadata metadata)
            throws JsonProcessingException {
        delete(jvmId, filename);
        create(jvmId, filename, metadata);
    }

    default void delete(String jvmId, String filename) {
        delete(RecordingHelper.archivedRecordingKey(jvmId, filename));
    }

    void delete(String storageKey);
}
