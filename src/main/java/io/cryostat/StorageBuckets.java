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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Utility for interacting with S3 object storage buckets. Use to ensure that the S3 object storage
 * meets the application requirements, ex. that the expected buckets exist.
 */
@ApplicationScoped
public class StorageBuckets {

    @Inject S3Client storage;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.STORAGE_BUCKETS_AVAILABILITY_CHECK_PERIOD)
    Duration checkPeriod;

    @ConfigProperty(name = ConfigProperties.STORAGE_BUCKETS_AVAILABILITY_CHECK_TIMEOUT)
    Duration checkTimeout;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public CompletableFuture<Boolean> waitForAvailability(String bucket) {
        var cf = new CompletableFuture<Boolean>();
        pool.submit(
                () -> {
                    Instant deadline = Instant.now().plus(checkTimeout);
                    while (Instant.now().isBefore(deadline)) {
                        try {
                            if (exists(bucket)) {
                                cf.complete(true);
                                return;
                            }
                            Thread.sleep(checkPeriod.toMillis());
                        } catch (Exception e) {
                            cf.completeExceptionally(e);
                            return;
                        }
                    }
                    cf.complete(false);
                });
        return cf;
    }

    private boolean exists(String bucket) {
        boolean exists = false;
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                                    .sdkHttpResponse()
                                    .statusCode());
        } catch (Exception e) {
            exists = false;
        }
        logger.debugv("Storage bucket \"{0}\" exists? {1}", bucket, exists);
        return exists;
    }

    void onStop(@Observes ShutdownEvent evt) {
        pool.shutdownNow();
    }
}
