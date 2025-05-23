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

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecordings.Metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@LookupIfProperty(
        name = ConfigProperties.ARCHIVED_RECORDINGS_METADATA_STORAGE_MODE,
        stringValue = NoopArchivedRecordingMetadataService.METADATA_STORAGE_MODE_NOOP)
/**
 * No-op implementation of {@link ArchivedRecordingMetadataService}. The default metadata storage
 * mode is 'tagging', which uses embedded object tags and is directly implemented within {@link
 * RecordingHelper}. {@link ArchivedRecordingMetadataService} instances should only be instantiated
 * and selected when the metadata storage is set to another mode. This implementation is therefore
 * injected as the default, but should never actually be invoked.
 */
class NoopArchivedRecordingMetadataService implements ArchivedRecordingMetadataService {

    static final String METADATA_STORAGE_MODE_NOOP = "noop";

    @Override
    public void create(String storageKey, Metadata metadata) throws JsonProcessingException {}

    @Override
    public Optional<Metadata> read(String storageKey) throws IOException {
        return Optional.empty();
    }

    @Override
    public void delete(String storageKey) {}
}
