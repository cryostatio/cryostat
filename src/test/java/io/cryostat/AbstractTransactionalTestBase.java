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

import static io.restassured.RestAssured.given;

import java.time.Duration;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.apache.http.client.utils.URLEncodedUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

public abstract class AbstractTransactionalTestBase {

    public static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
    public static String SELF_JMX_URL_ENCODED =
            URLEncodedUtils.formatSegments(SELF_JMX_URL).substring(1);
    public static final String SELFTEST_ALIAS = "selftest";

    @ConfigProperty(name = "storage.buckets.archives.name")
    String archivesBucket;

    @ConfigProperty(name = "test.storage.timeout", defaultValue = "5m")
    Duration storageTimeout;

    @ConfigProperty(name = "test.storage.retry", defaultValue = "5s")
    Duration storageRetry;

    @Inject Logger logger;
    @Inject S3Client storage;
    @Inject Flyway flyway;

    @BeforeEach
    void waitForStorage() throws InterruptedException {
        long totalTime = 0;
        while (!bucketExists(archivesBucket)) {
            long start = System.nanoTime();
            Thread.sleep(storageRetry.toMillis());
            long elapsed = System.nanoTime() - start;
            totalTime += elapsed;
            if (Duration.ofNanos(totalTime).compareTo(storageTimeout) > 0) {
                throw new IllegalStateException("Storage took too long to become ready");
            }
        }
    }

    private boolean bucketExists(String bucket) {
        boolean exists = false;
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
        return exists;
    }

    @BeforeEach
    void migrate() {
        flyway.migrate();
    }

    @AfterEach
    void cleanup() {
        flyway.clean();
        flyway.migrate();
    }

    protected int defineSelfCustomTarget() {
        return given().basePath("/")
                .log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("connectUrl", SELF_JMX_URL)
                .formParam("alias", SELFTEST_ALIAS)
                .when()
                .post("/api/v4/targets")
                .then()
                .log()
                .all()
                .extract()
                .jsonPath()
                .getInt("id");
    }
}
