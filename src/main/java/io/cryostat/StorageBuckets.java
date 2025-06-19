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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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

    private final BlockingQueue<String> buckets = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<String, CompletableFuture<Void>> locks =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService q = Executors.newScheduledThreadPool(2);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean shutdown = false;

    public CompletableFuture<Void> createIfNecessary(String bucket) {
        if (buckets.contains(bucket)) {
            logger.debugv("Bucket \"{0}\" already queued, skipping");
            return CompletableFuture.completedFuture(null);
        }
        var cf = locks.computeIfAbsent(bucket, k -> new CompletableFuture<Void>());
        logger.debugv("Queueing bucket check/creation: \"{0}\"", bucket);
        buckets.add(bucket);
        return cf;
    }

    private void tryCreate(String bucket) {
        var cf = locks.get(bucket);
        if (cf == null) {
            throw new IllegalStateException();
        }
        logger.debugv("Checking if storage bucket \"{0}\" exists ...", bucket);
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
        try {
            if (!exists) {
                logger.debugv("Attempting to create storage bucket \"{0}\" ...", bucket);
                storage.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                logger.debugv("Storage bucket \"{0}\" created", bucket);
            }
            cf.complete(null);
        } catch (Exception e) {
            q.schedule(
                    () -> buckets.add(bucket),
                    creationRetryPeriod.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    void onStart(@Observes StartupEvent evt) {
        q.submit(
                () -> {
                    while (!shutdown) {
                        try {
                            String bucket = buckets.take();
                            pool.execute(() -> tryCreate(bucket));
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
    }

    void onStop(@Observes ShutdownEvent evt) {
        shutdown = true;
        q.shutdownNow();
        locks.values().forEach(cf -> cf.complete(null));
        locks.clear();
        buckets.clear();
    }
}
