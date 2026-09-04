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
package io.cryostat.resources;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpStatusCodeIdentifier;

import org.rnorth.ducttape.TimeoutException;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Wait until the storage container's S3 API reports that every expected bucket exists.
 *
 * <p>The container binds its listening port before it has finished creating the buckets named by
 * {@code CRYOSTAT_BUCKETS}, so waiting only for the port to open hands tests a storage instance
 * which is reachable but still answers {@code 404 NoSuchBucket}. Tests then poll for the bucket
 * themselves, which turns a storage problem into a slow hang rather than a container startup
 * failure.
 *
 * @see io.cryostat.StorageBuckets
 */
public class S3BucketsWaitStrategy extends AbstractWaitStrategy {

    private final int containerPort;
    private final List<String> buckets;
    private final String accessKey;
    private final String secretKey;

    public S3BucketsWaitStrategy(
            int containerPort, List<String> buckets, String accessKey, String secretKey) {
        this.containerPort = containerPort;
        this.buckets = List.copyOf(buckets);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    protected void waitUntilReady() {
        URI endpoint =
                URI.create(
                        String.format(
                                "http://%s:%d",
                                waitStrategyTarget.getHost(),
                                waitStrategyTarget.getMappedPort(containerPort)));
        try (S3Client client = buildClient(endpoint)) {
            Unreliables.retryUntilTrue(
                    (int) startupTimeout.toMillis(),
                    TimeUnit.MILLISECONDS,
                    () -> getRateLimiter().getWhenReady(() -> allBucketsExist(client)));
        } catch (TimeoutException te) {
            throw new ContainerLaunchException(
                    String.format(
                            "Timed out waiting for storage buckets %s to be created at %s",
                            buckets, endpoint));
        }
    }

    private S3Client buildClient(URI endpoint) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    private boolean allBucketsExist(S3Client client) {
        return buckets.stream().allMatch(b -> bucketExists(client, b));
    }

    private boolean bucketExists(S3Client client, String bucket) {
        try {
            return HttpStatusCodeIdentifier.isSuccessCode(
                    client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                            .sdkHttpResponse()
                            .statusCode());
        } catch (Exception e) {
            return false;
        }
    }
}
