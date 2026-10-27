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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.cryostat.ConfigProperties;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Centralized configuration service for storage bucket and directory resolution.
 *
 * <p>Resolves v5.0 storage configuration that organizes blob data into shared buckets with
 * subdirectory prefixes scoped by JVM ID, and templates into separate shared buckets.
 *
 * <p>Supports dynamic resource type discovery from application.properties configuration.
 */
@ApplicationScoped
public class StorageConfiguration {

    @Inject Config config;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.STORAGE_BUCKETS_METADATA_NAME)
    String metadataBucketName;

    private volatile Map<String, StorageInfo> cache = new HashMap<>();

    public record StorageInfo(String bucketName, String directoryName) {
        public StorageInfo {
            Objects.requireNonNull(bucketName, "bucketName");
            Objects.requireNonNull(directoryName, "directoryName");
        }
    }

    /**
     * Get storage information (bucket name and directory name) for a resource type.
     *
     * @param resourceType the resource type
     * @return StorageInfo with resolved bucket and directory names
     * @throws IllegalStateException if required configuration properties are missing or
     *     resourceType is not configured
     */
    public StorageInfo getStorageInfo(ConfigProperties.StorageResourceType resourceType) {
        return cache.computeIfAbsent(resourceType.id(), this::resolveStorageInfo);
    }

    /**
     * Get metadata storage information for a resource type.
     *
     * <p>Metadata uses a dedicated bucket with per-resource-type subdirectories.
     *
     * @param resourceType the resource type
     * @return StorageInfo with resolved metadata bucket and directory names
     * @throws IllegalStateException if required configuration properties are missing or
     *     resourceType is not configured
     */
    public StorageInfo getMetadataStorageInfo(ConfigProperties.StorageResourceType resourceType) {
        String cacheKey = "metadata." + resourceType.id();
        return cache.computeIfAbsent(cacheKey, _ -> resolveMetadataStorageInfo(resourceType.id()));
    }

    /**
     * Get the full storage path prefix for blob data scoped by JVM ID.
     *
     * <p>Path format: {@code {directoryName}/{jvmHashId}}
     *
     * @param resourceType the resource type
     * @param jvmHashId the target JVM's hash ID
     * @return path prefix for blob storage
     */
    public String getBlobStoragePath(
            ConfigProperties.StorageResourceType resourceType, String jvmHashId) {
        StorageInfo info = getStorageInfo(resourceType);
        return String.format("%s/%s", info.directoryName, jvmHashId);
    }

    /**
     * Get the storage path prefix for template data.
     *
     * <p>Path format: {@code {directoryName}}
     *
     * @param resourceType the resource type
     * @return path prefix for template storage
     */
    public String getTemplateStoragePath(ConfigProperties.StorageResourceType resourceType) {
        StorageInfo info = getStorageInfo(resourceType);
        return info.directoryName;
    }

    /**
     * Get the metadata storage path for a resource identifier.
     *
     * <p>Path format: {@code {directoryName}/{identifier}}
     *
     * @param resourceType the resource type
     * @param identifier the resource's unique identifier
     * @return path prefix for metadata storage
     */
    public String getMetadataStoragePath(
            ConfigProperties.StorageResourceType resourceType, String identifier) {
        StorageInfo info = getMetadataStorageInfo(resourceType);
        return String.format("%s/%s", info.directoryName, identifier);
    }

    /**
     * Get the bucket name for a resource type.
     *
     * @param resourceType the resource type
     * @return the bucket name
     */
    public String getBucketName(ConfigProperties.StorageResourceType resourceType) {
        return getStorageInfo(resourceType).bucketName;
    }

    /**
     * Get the metadata bucket name.
     *
     * @return the metadata bucket name
     */
    public String getMetadataBucketName() {
        return metadataBucketName;
    }

    private StorageInfo resolveStorageInfo(String resourceType) {
        validateResourceTypeConfigured(resourceType);

        String bucketPropName =
                String.format("%s.%s.name", ConfigProperties.STORAGE_BUCKETS_PREFIX, resourceType);
        String dirPropName =
                String.format(
                        "%s.%s.name", ConfigProperties.STORAGE_DIRECTORIES_PREFIX, resourceType);

        String bucket =
                config.getOptionalValue(bucketPropName, String.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing required storage configuration: "
                                                        + bucketPropName));
        String directory =
                config.getOptionalValue(dirPropName, String.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing required storage configuration: "
                                                        + dirPropName));

        logger.debugv(
                "Resolved storage info for {0}: bucket={1}, directory={2}",
                resourceType, bucket, directory);
        return new StorageInfo(bucket, directory);
    }

    private StorageInfo resolveMetadataStorageInfo(String resourceType) {
        String bucketPropName = ConfigProperties.STORAGE_BUCKETS_METADATA_NAME;
        String dirPropName =
                String.format(
                        "%s.%s.name",
                        ConfigProperties.STORAGE_DIRECTORIES_METADATA_PREFIX, resourceType);

        String bucket =
                config.getOptionalValue(bucketPropName, String.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing required storage configuration: "
                                                        + bucketPropName));
        String directory =
                config.getOptionalValue(dirPropName, String.class)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing required storage configuration: "
                                                        + dirPropName));

        logger.debugv(
                "Resolved metadata storage info for {0}: bucket={1}, directory={2}",
                resourceType, bucket, directory);
        return new StorageInfo(bucket, directory);
    }

    private void validateResourceTypeConfigured(String resourceType) {
        String bucketPropName =
                String.format("%s.%s.name", ConfigProperties.STORAGE_BUCKETS_PREFIX, resourceType);
        if (!config.getOptionalValue(bucketPropName, String.class).isPresent()) {
            throw new IllegalStateException(
                    "Resource type '"
                            + resourceType
                            + "' is not configured. Property not found: "
                            + bucketPropName);
        }
    }
}
