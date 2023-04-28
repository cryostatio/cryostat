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
package io.cryostat.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToOne;
import javax.persistence.PrePersist;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.HttpHeaders;

import io.cryostat.credentials.Credential;

import io.quarkiverse.hibernate.types.json.JsonBinaryType;
import io.quarkiverse.hibernate.types.json.JsonTypes;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.TypeDef;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(DiscoveryPlugin.Listener.class)
@TypeDef(name = JsonTypes.JSON_BIN, typeClass = JsonBinaryType.class)
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

                Credential credential = null;
                if (StringUtils.isNotBlank(userInfo) && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":");
                    if ("storedcredentials".equals(parts[0])) {
                        logger.infov(
                                "Using stored credentials id:{0} referenced in ping callback"
                                        + " userinfo",
                                parts[1]);

                        credential = Credential.find("id", Long.parseLong(parts[1])).singleResult();
                    }
                }

                requestContext
                        .getHeaders()
                        .add(
                                HttpHeaders.AUTHORIZATION,
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        (credential.username
                                                                        + ":"
                                                                        + credential.password)
                                                                .getBytes()));
            }
        }
    }
}
