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
package itest.util;

import java.net.URI;

import io.cryostat.core.sys.Environment;
import io.cryostat.util.HttpStatusCodeIdentifier;

import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.HttpHeaders;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.ext.web.client.WebClient;
import org.jboss.logging.Logger;

public class Utils {

    public static final int WEB_PORT;
    public static final String WEB_HOST;

    static {
        Environment env = new Environment();
        WEB_PORT = Integer.valueOf(env.getEnv("QUARKUS_HTTP_PORT", "8081"));
        WEB_HOST = "localhost";
    }

    public static final HttpClientOptions HTTP_CLIENT_OPTIONS;

    static {
        HTTP_CLIENT_OPTIONS =
                new HttpClientOptions()
                        .setSsl(false)
                        .setTrustAll(true)
                        .setVerifyHost(false)
                        .setDefaultHost(WEB_HOST)
                        .setDefaultPort(WEB_PORT)
                        .setLogActivity(true);
    }

    private static final Vertx VERTX =
            Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
    public static final HttpClient HTTP_CLIENT =
            VERTX.createHttpClient(HTTP_CLIENT_OPTIONS)
                    .redirectHandler(
                            resp -> {
                                Logger logger = Logger.getLogger(WebClient.class);
                                // see io.vertx.core.http.impl.HttpClientImpl.DEFAULT_HANDLER
                                // lightly modified version of the default handler. Ignores handling
                                // of specific HTTP GET and HEAD methods and allows any verb to be
                                // redirected, and does not rewrite port numbers
                                try {
                                    int statusCode = resp.statusCode();
                                    String location = resp.getHeader(HttpHeaders.LOCATION);
                                    if (location == null
                                            || !HttpStatusCodeIdentifier.isRedirectCode(
                                                    statusCode)) {
                                        logger.infov(
                                                "Ignoring non-redirect request: {0} {1}",
                                                location, statusCode);
                                        return null;
                                    }
                                    HttpMethod m = resp.request().getMethod();
                                    URI uri =
                                            HttpUtils.resolveURIReference(
                                                    resp.request().absoluteURI(), location);
                                    boolean ssl;
                                    int port = uri.getPort();
                                    String protocol = uri.getScheme();
                                    char chend = protocol.charAt(protocol.length() - 1);
                                    if (chend == 'p') {
                                        ssl = false;
                                    } else if (chend == 's') {
                                        ssl = true;
                                    } else {
                                        return null;
                                    }
                                    String requestURI = uri.getPath();
                                    if (requestURI == null || requestURI.isEmpty()) {
                                        requestURI = "/";
                                    }
                                    String query = uri.getQuery();
                                    if (query != null) {
                                        requestURI += "?" + query;
                                    }
                                    RequestOptions options = new RequestOptions();
                                    options.setMethod(m);
                                    options.setHost(uri.getHost());
                                    options.setPort(port);
                                    options.setSsl(ssl);
                                    options.setURI(requestURI);
                                    options.setHeaders(resp.request().headers());
                                    options.removeHeader(HttpHeaders.CONTENT_LENGTH);
                                    logger.infov("Redirecting request to: {0}", options.toJson());
                                    return Future.succeededFuture(options);
                                } catch (Exception e) {
                                    return Future.failedFuture(e);
                                }
                            });
    private static final WebClient WEB_CLIENT_INSTANCE = WebClient.wrap(HTTP_CLIENT);

    public static WebClient getWebClient() {
        return WEB_CLIENT_INSTANCE;
    }

    public static FileSystem getFileSystem() {
        return VERTX.fileSystem();
    }
}
