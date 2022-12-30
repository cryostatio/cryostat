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

import java.util.Map;
import java.util.Optional;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
class Health {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @ConfigProperty(name = "quarkus.http.host")
    String host;

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    @ConfigProperty(name = "quarkus.http.ssl-port")
    int sslPort;

    @ConfigProperty(name = "quarkus.http.ssl.certificate.key-store-password")
    Optional<String> sslPass;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @GET
    @Path("health")
    @PermitAll
    public Response health() {
        return Response.ok(
                        Map.of(
                                "cryostatVersion",
                                version,
                                "dashboardConfigured",
                                false,
                                "dashboardAvailable",
                                false,
                                "datasourceConfigured",
                                false,
                                "datasourceAvailable",
                                false,
                                "reportsConfigured",
                                false,
                                "reportsAvailable",
                                false))
                .header("Access-Control-Allow-Origin", "http://localhost:9000")
                .header(
                        "Access-Control-Allow-Headers",
                        "accept, origin, authorization, content-type,"
                                + " x-requested-with, x-jmx-authorization")
                .header("Access-Control-Expose-Headers", "x-www-authenticate, x-jmx-authenticate")
                .header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
    }

    @GET
    @Path("health/liveness")
    @PermitAll
    public void liveness() {}

    @GET
    @Path("api/v1/notifications_url")
    @PermitAll
    public Response notificationsUrl() {
        // TODO @PermitAll annotation seems to skip the CORS filter, so these headers don't get
        // added. We shouldn't need to add them manually like this and they should not be added in
        // prod builds.
        boolean ssl = sslPass.isPresent();
        return Response.ok(
                        Map.of(
                                "notificationsUrl",
                                String.format(
                                        "%s://%s:%d/api/v1/notifications",
                                        ssl ? "wss" : "ws", host, ssl ? sslPort : port)))
                .header("Access-Control-Allow-Origin", "http://localhost:9000")
                .header(
                        "Access-Control-Allow-Headers",
                        "accept, origin, authorization, content-type,"
                                + " x-requested-with, x-jmx-authorization")
                .header("Access-Control-Expose-Headers", "x-www-authenticate, x-jmx-authenticate")
                .header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
    }
}
