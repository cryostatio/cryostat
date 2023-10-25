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
package io.cryostat.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import io.cryostat.credentials.Credential;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(DiscoveryPlugin.Listener.class)
public class DiscoveryPlugin extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    public UUID id;

    @OneToOne(
            optional = false,
            cascade = {CascadeType.ALL},
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    public DiscoveryNode realm;

    @Column(unique = true, updatable = false)
    @Convert(converter = UriConverter.class)
    public URI callback;

    public boolean builtin;

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;

        @PrePersist
        public void prePersist(DiscoveryPlugin plugin) {
            if (plugin.builtin) {
                return;
            }
            if (plugin.callback == null) {
                throw new IllegalArgumentException();
            }
            try {
                PluginCallback.create(plugin).ping();
                logger.infov(
                        "Registered discovery plugin: {0} @ {1}",
                        plugin.realm.name, plugin.callback);
            } catch (Exception e) {
                logger.error("Discovery Plugin ping failed", e);
            }
        }
    }

    @Path("")
    interface PluginCallback {

        @GET
        public void ping();

        public static PluginCallback create(DiscoveryPlugin plugin) throws URISyntaxException {
            PluginCallback client =
                    RestClientBuilder.newBuilder()
                            .baseUri(plugin.callback)
                            .register(AuthorizationFilter.class)
                            .build(PluginCallback.class);
            return client;
        }

        public static class AuthorizationFilter implements ClientRequestFilter {

            final Logger logger = Logger.getLogger(PluginCallback.class);

            @Override
            public void filter(ClientRequestContext requestContext) throws IOException {
                String userInfo = requestContext.getUri().getUserInfo();
                if (StringUtils.isBlank(userInfo)) {
                    return;
                }

                if (StringUtils.isNotBlank(userInfo) && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":");
                    if (parts.length == 2 && "storedcredentials".equals(parts[0])) {
                        logger.infov(
                                "Using stored credentials id:{0} referenced in ping callback"
                                        + " userinfo",
                                parts[1]);

                        Credential credential =
                                Credential.find("id", Long.parseLong(parts[1])).singleResult();

                        requestContext
                                .getHeaders()
                                .add(
                                        HttpHeaders.AUTHORIZATION,
                                        "Basic "
                                                + Base64.getEncoder()
                                                        .encodeToString(
                                                                (credential.username
                                                                                + ":"
                                                                                + credential
                                                                                        .password)
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)));
                    } else {
                        throw new IllegalStateException("Unexpected credential format");
                    }
                } else {
                    throw new IOException(
                            new BadRequestException(
                                    "No credentials provided and none found in storage"));
                }
            }
        }
    }
}
