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
package io.cryostat;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Utility for interacting with S3 object storage buckets. Use to ensure that the S3 object storage
 * meets the application requirements, ex. that the expected buckets exist.
 */
@ApplicationScoped
public class StorageBuckets {

    @Inject S3Client storage;
    @Inject Logger logger;

    @ConfigProperty(name = "storage.buckets.creation-retry.period")
    Duration creationRetryPeriod;

    private final Set<String> buckets = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    public void createIfNecessary(String bucket) {
        buckets.add(bucket);
    }

    private boolean tryCreate(String bucket) {
        boolean exists = false;
        logger.debugv("Checking if storage bucket \"{0}\" exists ...", bucket);
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                                    .sdkHttpResponse()
                                    .statusCode());
            logger.debugv("Storage bucket \"{0}\" exists? {1}", bucket, exists);
        } catch (Exception e) {
            logger.warn(e);
        }
        if (!exists) {
            logger.debugv("Attempting to create storage bucket \"{0}\" ...", bucket);
            try {
                storage.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                logger.debugv("Storage bucket \"{0}\" created", bucket);
            } catch (Exception e) {
                logger.warn(e);
                return false;
            }
        }
        return true;
    }

    void onStart(@Observes StartupEvent evt) {
        worker.scheduleAtFixedRate(
                () -> {
                    var it = buckets.iterator();
                    while (it.hasNext()) {
                        if (tryCreate(it.next())) it.remove();
                    }
                },
                0,
                creationRetryPeriod.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent evt) {
        worker.shutdown();
    }
}
