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

import java.io.IOException;
import java.util.Optional;

import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.util.CRUDService;

public interface DiagnosticsMetadataService extends CRUDService<String, Metadata, Metadata> {

    public static final String METADATA_STORAGE_MODE_TAGGING = "tagging";
    public static final String METADATA_STORAGE_MODE_OBJECTMETA = "metadata";
    public static final String METADATA_STORAGE_MODE_BUCKET = "bucket";

    default void create(String jvmId, String filename, Metadata metadata) throws IOException {
        create(DiagnosticsHelper.storageKey(jvmId, filename), metadata);
    }

    default Optional<Metadata> read(String jvmId, String filename) throws IOException {
        return read(DiagnosticsHelper.storageKey(jvmId, filename));
    }

    Optional<Metadata> read(String storageKey) throws IOException;

    default void update(String jvmId, String filename, Metadata metadata) throws IOException {
        delete(jvmId, filename);
        create(jvmId, filename, metadata);
    }

    default void delete(String jvmId, String filename) throws IOException {
        delete(DiagnosticsHelper.storageKey(jvmId, filename));
    }

    static enum StorageMode {
        TAGGING(METADATA_STORAGE_MODE_TAGGING),
        METADATA(METADATA_STORAGE_MODE_OBJECTMETA),
        BUCKET(METADATA_STORAGE_MODE_BUCKET),
        ;
        private final String key;

        private StorageMode(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }
}