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
package io.cryostat.targets;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.libcryostat.sys.Environment;
import io.cryostat.libcryostat.sys.FileSystem;

import io.quarkus.arc.DefaultBean;
import io.vertx.core.Vertx;
import io.vertx.core.net.SSLOptions;
import io.vertx.core.net.TrustOptions;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Singleton
public class TargetsModule {

    static final String AGENT_CLIENT = "AGENT_CLIENT";

    @Produces
    @DefaultBean
    public JFRConnectionToolkit provideJfrConnectionToolkit() {
        var log = org.slf4j.LoggerFactory.getLogger(JFRConnectionToolkit.class);
        return new JFRConnectionToolkit(log::warn, new FileSystem(), new Environment());
    }

    @Produces
    @Singleton
    @Named(AGENT_CLIENT)
    public WebClient provideAgentWebClient(
            Vertx vertx,
            @ConfigProperty(name = ConfigProperties.AGENT_TLS_VERIFY_HOST) boolean verifyHost,
            Logger logger) {
        logger.infov(
                "Creating {0} WebClient {1}={2}",
                AGENT_CLIENT, ConfigProperties.AGENT_TLS_VERIFY_HOST, verifyHost);
        io.vertx.ext.web.client.WebClient delegate =
                io.vertx.ext.web.client.WebClient.create(vertx);
        WebClient wc = new WebClient(delegate);
        SSLOptions opts = new SSLOptions();
        if (!verifyHost) {
            TrustOptions trust =
                    TrustOptions.wrap(
                            new X509TrustManager() {
                                @Override
                                public void checkClientTrusted(
                                        X509Certificate[] chain, String authType)
                                        throws CertificateException {}

                                @Override
                                public void checkServerTrusted(
                                        X509Certificate[] chain, String authType)
                                        throws CertificateException {}

                                @Override
                                public X509Certificate[] getAcceptedIssuers() {
                                    return new X509Certificate[0];
                                }
                            });
            opts.setTrustOptions(trust);
        }
        wc.updateSSLOptionsAndAwait(opts);
        return wc;
    }
}
