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
package io.cryostat.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import io.cryostat.ConfigProperties;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(StorageConfigurationTest.StorageConfigProfile.class)
class StorageConfigurationTest {

    @Inject StorageConfiguration config;

    @Test
    void testGetStorageInfoForArchivedRecordings() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        assertEquals("test-blob-bucket", info.bucketName());
        assertEquals("archivedrecordings", info.directoryName());
    }

    @Test
    void testGetStorageInfoForHeapDumps() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.HEAP_DUMPS);
        assertEquals("test-blob-bucket", info.bucketName());
        assertEquals("heapdumps", info.directoryName());
    }

    @Test
    void testGetStorageInfoForThreadDumps() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.THREAD_DUMPS);
        assertEquals("test-blob-bucket", info.bucketName());
        assertEquals("threaddumps", info.directoryName());
    }

    @Test
    void testGetStorageInfoForUnifiedLogs() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.UNIFIED_LOGS);
        assertEquals("test-blob-bucket", info.bucketName());
        assertEquals("unifiedlogs", info.directoryName());
    }

    @Test
    void testGetStorageInfoForAsyncProfiler() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.ASYNC_PROFILER);
        assertEquals("test-blob-bucket", info.bucketName());
        assertEquals("asyncprofiler", info.directoryName());
    }

    @Test
    void testGetStorageInfoForEventTemplates() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.EVENT_TEMPLATES);
        assertEquals("test-templates-bucket", info.bucketName());
        assertEquals("eventtemplates", info.directoryName());
    }

    @Test
    void testGetStorageInfoForJmcProbeTemplates() {
        StorageConfiguration.StorageInfo info =
                config.getStorageInfo(ConfigProperties.StorageResourceType.JMC_PROBE_TEMPLATES);
        assertEquals("test-templates-bucket", info.bucketName());
        assertEquals("jmcprobetemplates", info.directoryName());
    }

    @Test
    void testGetMetadataStorageInfoForArchivedRecordings() {
        StorageConfiguration.StorageInfo info =
                config.getMetadataStorageInfo(
                        ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        assertEquals("test-metadata-bucket", info.bucketName());
        assertEquals("archivedrecordings", info.directoryName());
    }

    @Test
    void testGetMetadataStorageInfoForHeapDumps() {
        StorageConfiguration.StorageInfo info =
                config.getMetadataStorageInfo(ConfigProperties.StorageResourceType.HEAP_DUMPS);
        assertEquals("test-metadata-bucket", info.bucketName());
        assertEquals("heapdumps", info.directoryName());
    }

    @Test
    void testGetBucketName() {
        assertEquals(
                "test-blob-bucket",
                config.getBucketName(ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS));
        assertEquals(
                "test-templates-bucket",
                config.getBucketName(ConfigProperties.StorageResourceType.EVENT_TEMPLATES));
    }

    @Test
    void testGetMetadataBucketName() {
        assertEquals("test-metadata-bucket", config.getMetadataBucketName());
    }

    @Test
    void testGetBlobStoragePath() {
        String path =
                config.getBlobStoragePath(
                        ConfigProperties.StorageResourceType.HEAP_DUMPS, "abc123def456");
        assertEquals("heapdumps/abc123def456", path);
    }

    @Test
    void testGetTemplateStoragePath() {
        String path =
                config.getTemplateStoragePath(ConfigProperties.StorageResourceType.EVENT_TEMPLATES);
        assertEquals("eventtemplates", path);
    }

    @Test
    void testGetMetadataStoragePath() {
        String path =
                config.getMetadataStoragePath(
                        ConfigProperties.StorageResourceType.HEAP_DUMPS,
                        "abc123def456/heap-uuid-12345");
        assertEquals("heapdumps/abc123def456/heap-uuid-12345", path);
    }

    @Test
    void testCaching() {
        StorageConfiguration.StorageInfo info1 =
                config.getStorageInfo(ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        StorageConfiguration.StorageInfo info2 =
                config.getStorageInfo(ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        assertSame(info1, info2, "StorageInfo should be cached and return same instance");
    }

    @Test
    void testStorageInfoRecord() {
        StorageConfiguration.StorageInfo info =
                new StorageConfiguration.StorageInfo("bucket", "directory");
        assertEquals("bucket", info.bucketName());
        assertEquals("directory", info.directoryName());
    }

    @Test
    void testStorageInfoRecordEquality() {
        StorageConfiguration.StorageInfo info1 =
                new StorageConfiguration.StorageInfo("bucket", "directory");
        StorageConfiguration.StorageInfo info2 =
                new StorageConfiguration.StorageInfo("bucket", "directory");
        assertEquals(info1, info2);
    }

    public static class StorageConfigProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("storage.buckets.archived-recordings.name", "test-blob-bucket"),
                    Map.entry("storage.directories.archived-recordings.name", "archivedrecordings"),
                    Map.entry("storage.buckets.heap-dumps.name", "test-blob-bucket"),
                    Map.entry("storage.directories.heap-dumps.name", "heapdumps"),
                    Map.entry("storage.buckets.thread-dumps.name", "test-blob-bucket"),
                    Map.entry("storage.directories.thread-dumps.name", "threaddumps"),
                    Map.entry("storage.buckets.unified-logs.name", "test-blob-bucket"),
                    Map.entry("storage.directories.unified-logs.name", "unifiedlogs"),
                    Map.entry("storage.buckets.async-profiler.name", "test-blob-bucket"),
                    Map.entry("storage.directories.async-profiler.name", "asyncprofiler"),
                    Map.entry("storage.buckets.event-templates.name", "test-templates-bucket"),
                    Map.entry("storage.directories.event-templates.name", "eventtemplates"),
                    Map.entry("storage.buckets.jmc-probe-templates.name", "test-templates-bucket"),
                    Map.entry("storage.directories.jmc-probe-templates.name", "jmcprobetemplates"),
                    Map.entry("storage.buckets.metadata.name", "test-metadata-bucket"),
                    Map.entry(
                            "storage.directories.metadata.archived-recordings.name",
                            "archivedrecordings"),
                    Map.entry("storage.directories.metadata.heap-dumps.name", "heapdumps"),
                    Map.entry("storage.directories.metadata.thread-dumps.name", "threaddumps"),
                    Map.entry("storage.directories.metadata.unified-logs.name", "unifiedlogs"),
                    Map.entry(
                            "storage.directories.metadata.event-templates.name", "eventtemplates"),
                    Map.entry(
                            "storage.directories.metadata.jmc-probe-templates.name",
                            "jmcprobetemplates"),
                    // Test case: bucket exists but directory doesn't
                    Map.entry("storage.buckets.missing-directory-type.name", "test-bucket"),
                    // Test case: directory exists but bucket doesn't (will be caught by bucket
                    // lookup)
                    Map.entry("storage.directories.nonexistent-type.name", "somedir"));
        }
    }
}
