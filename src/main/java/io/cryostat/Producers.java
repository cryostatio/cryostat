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

import java.net.URI;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.sys.FileSystem;

import io.quarkus.arc.DefaultBean;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.projectnessie.cel.tools.ScriptHost;
import org.projectnessie.cel.types.jackson.JacksonRegistry;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.utils.StringUtils;

public class Producers {

    public static final String BASE64_URL = "BASE64_URL";

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static Clock produceClock() {
        return new Clock();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static FileSystem produceFileSystem() {
        return new FileSystem();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    @Named(BASE64_URL)
    public static Base64 produceBase64Url() {
        return new Base64(0, null, true);
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static ScheduledExecutorService produceScheduledExecutorService() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    @Produces
    @RequestScoped
    @DefaultBean
    public static InterruptibleReportGenerator produceInterruptibleReportGenerator() {
        return new InterruptibleReportGenerator(
                io.cryostat.core.log.Logger.INSTANCE, Set.of(), Executors.newCachedThreadPool());
    }

    @Produces
    @DefaultBean
    public WebClient produceWebClient(Vertx vertx) {
        return WebClient.create(vertx);
    }

    @Produces
    @ApplicationScoped
    public static S3Presigner produceS3Presigner(
            @ConfigProperty(name = "quarkus.s3.endpoint-override") String endpointOverride,
            @ConfigProperty(name = "quarkus.s3.dualstack") boolean dualstack,
            @ConfigProperty(name = "quarkus.s3.aws.region") String region,
            @ConfigProperty(name = "quarkus.s3.path-style-access") boolean pathStyleAccess) {
        S3Presigner.Builder builder =
                S3Presigner.builder()
                        .region(Region.of(region))
                        .dualstackEnabled(dualstack)
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(pathStyleAccess)
                                        .build());

        if (StringUtils.isNotBlank(endpointOverride)) {
            builder = builder.endpointOverride(URI.create(endpointOverride));
        }

        return builder.build();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static ScriptHost produceScriptHost() {
        return ScriptHost.newBuilder().registry(JacksonRegistry.newRegistry()).build();
    }
}
