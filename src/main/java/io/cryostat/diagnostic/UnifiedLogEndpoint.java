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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.util.StringUtils;

import io.quarkus.security.PermissionsAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;

@Path("/api/v5/diagnostics/unified-logs")
public class UnifiedLogEndpoint {

    @Inject Logger log;
    @Inject DiagnosticsHelper helper;

    @PermissionsAllowed(value = "unifiedlogs:read", inclusive = true)
    @GET
    public Collection<ArchivedUnifiedLogDirectory> listUnifiedLogs() {
        var map = new HashMap<String, ArchivedUnifiedLogDirectory>();
        helper.listUnifiedLogObjects()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedUnifiedLogDirectory(
                                                            id, new ArrayList<>()));
                            String storageKey = DiagnosticsHelper.storageKey(jvmId, filename);
                            Metadata metadata =
                                    helper.getUnifiedLogMetadata(storageKey)
                                            .orElse(Metadata.empty());
                            dir.unifiedLogs()
                                    .add(
                                            new UnifiedLogs.UnifiedLog(
                                                    jvmId,
                                                    helper.unifiedLogDownloadUrl(jvmId, filename),
                                                    filename,
                                                    item.lastModified().getEpochSecond(),
                                                    item.size(),
                                                    metadata));
                        });
        return map.values();
    }

    public record ArchivedUnifiedLogDirectory(
            String jvmId, Collection<UnifiedLogs.UnifiedLog> unifiedLogs) {
        public ArchivedUnifiedLogDirectory(
                String jvmId, Collection<UnifiedLogs.UnifiedLog> unifiedLogs) {
            this.jvmId = StringUtils.requireNonBlank(jvmId);
            this.unifiedLogs = new ArrayList<>(unifiedLogs);
        }

        public Collection<UnifiedLogs.UnifiedLog> unifiedLogs() {
            return Collections.unmodifiableCollection(this.unifiedLogs);
        }
    }
}
