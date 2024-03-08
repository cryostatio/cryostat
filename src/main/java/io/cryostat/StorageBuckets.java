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

import io.cryostat.util.HttpStatusCodeIdentifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@ApplicationScoped
public class StorageBuckets {

    @Inject S3Client storage;
    @Inject Logger logger;

    public void createIfNecessary(String bucket) {
        boolean exists = false;
        logger.infov("Checking if storage bucket \"{0}\" exists ...", bucket);
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                                    .sdkHttpResponse()
                                    .statusCode());
            logger.infov("Storage bucket \"{0}\" exists? {1}", bucket, exists);
        } catch (Exception e) {
            logger.info(e);
        }
        if (!exists) {
            logger.infov("Attempting to create storage bucket \"{0}\" ...", bucket);
            try {
                storage.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                logger.infov("Storage bucket \"{0}\" created", bucket);
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }
}
