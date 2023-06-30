/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import io.cryostat.core.sys.Clock;

import io.quarkus.arc.DefaultBean;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.utils.StringUtils;

public class Producers {

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static Clock produceClock() {
        return new Clock();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public static ScheduledExecutorService produceScheduledExecutorService() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    @Produces
    @DefaultBean
    public WebClient provideWebClient(Vertx vertx) {
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
    public static ScriptEngine provideScriptEngine() {
        return new ScriptEngineManager().getEngineByName("nashorn");
    }
}
