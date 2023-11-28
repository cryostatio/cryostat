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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.core.sys.Environment;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.WebClientBase;

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
    public static final HttpClient HTTP_CLIENT = VERTX.createHttpClient(HTTP_CLIENT_OPTIONS);

    private static TestWebClient WEB_CLIENT_INSTANCE;

    public static TestWebClient getWebClient() {
        if (WEB_CLIENT_INSTANCE == null) {
            synchronized (Utils.class) {
                if (WEB_CLIENT_INSTANCE == null) {
                    WebClientOptions options = new WebClientOptions(HTTP_CLIENT_OPTIONS);
                    WEB_CLIENT_INSTANCE = new TestWebClient(HTTP_CLIENT, options);
                }
            }
        }
        return WEB_CLIENT_INSTANCE;
    }

    public static FileSystem getFileSystem() {
        return VERTX.fileSystem();
    }

    public interface RedirectExtensions {
        HttpResponse<Buffer> post(String url, boolean authentication, MultiMap form, int timeout)
                throws InterruptedException, ExecutionException, TimeoutException;

        HttpResponse<Buffer> delete(String url, boolean authentication, int timeout)
                throws InterruptedException, ExecutionException, TimeoutException;

        HttpResponse<Buffer> patch(
                String url, boolean authentication, MultiMap headers, Buffer payload, int timeout)
                throws InterruptedException, ExecutionException, TimeoutException;

        HttpResponse<Buffer> patch(
                String url, boolean authentication, MultiMap headers, MultiMap payload, int timeout)
                throws InterruptedException, ExecutionException, TimeoutException;
    }

    public static class TestWebClient extends WebClientBase {

        private final RedirectExtensions extensions;

        public TestWebClient(HttpClient client, WebClientOptions options) {
            super(client, options);
            this.extensions = new RedirectExtensionsImpl();
        }

        public RedirectExtensions extensions() {
            return extensions;
        }

        private class RedirectExtensionsImpl implements RedirectExtensions {
            public HttpResponse<Buffer> post(
                    String url, boolean authentication, MultiMap form, int timeout)
                    throws InterruptedException, ExecutionException, TimeoutException {
                CompletableFuture<HttpResponse<Buffer>> future = new CompletableFuture<>();
                RequestOptions options = new RequestOptions().setURI(url);
                HttpRequest<Buffer> req = TestWebClient.this.request(HttpMethod.POST, options);
                if (authentication) {
                    req.basicAuthentication("user", "pass");
                }
                if (form != null) {
                    req.sendForm(
                            form,
                            ar -> {
                                if (ar.succeeded()) {
                                    future.complete(ar.result());
                                } else {
                                    future.completeExceptionally(ar.cause());
                                }
                            });
                } else {
                    req.send(
                            ar -> {
                                if (ar.succeeded()) {
                                    future.complete(ar.result());
                                } else {
                                    future.completeExceptionally(ar.cause());
                                }
                            });
                }
                if (future.get().statusCode() == 308) {
                    return post(future.get().getHeader("Location"), authentication, form, timeout);
                }
                return future.get(timeout, TimeUnit.SECONDS);
            }

            public HttpResponse<Buffer> delete(String url, boolean authentication, int timeout)
                    throws InterruptedException, ExecutionException, TimeoutException {
                CompletableFuture<HttpResponse<Buffer>> future = new CompletableFuture<>();
                RequestOptions options = new RequestOptions().setURI(url);
                HttpRequest<Buffer> req = TestWebClient.this.request(HttpMethod.DELETE, options);
                if (authentication) {
                    req.basicAuthentication("user", "pass");
                }
                req.send(
                        ar -> {
                            if (ar.succeeded()) {
                                future.complete(ar.result());
                            } else {
                                future.completeExceptionally(ar.cause());
                            }
                        });
                if (future.get().statusCode() == 308) {
                    return delete(future.get().getHeader("Location"), true, timeout);
                }
                return future.get(timeout, TimeUnit.SECONDS);
            }

            public HttpResponse<Buffer> patch(
                    String url,
                    boolean authentication,
                    MultiMap headers,
                    Buffer payload,
                    int timeout)
                    throws InterruptedException, ExecutionException, TimeoutException {
                CompletableFuture<HttpResponse<Buffer>> future = new CompletableFuture<>();
                RequestOptions options = new RequestOptions().setURI(url);
                HttpRequest<Buffer> req = TestWebClient.this.request(HttpMethod.PATCH, options);
                if (authentication) {
                    req.basicAuthentication("user", "pass");
                }
                if (headers != null) {
                    req.putHeaders(headers);
                }
                req.sendBuffer(
                        payload,
                        ar -> {
                            if (ar.succeeded()) {
                                future.complete(ar.result());
                            } else {
                                future.completeExceptionally(ar.cause());
                            }
                        });
                if (future.get().statusCode() == 308) {
                    return patch(
                            future.get().getHeader("Location"), true, headers, payload, timeout);
                }
                return future.get(timeout, TimeUnit.SECONDS);
            }

            public HttpResponse<Buffer> patch(
                    String url,
                    boolean authentication,
                    MultiMap headers,
                    MultiMap payload,
                    int timeout)
                    throws InterruptedException, ExecutionException, TimeoutException {
                CompletableFuture<HttpResponse<Buffer>> future = new CompletableFuture<>();
                RequestOptions options = new RequestOptions().setURI(url);
                HttpRequest<Buffer> req = TestWebClient.this.request(HttpMethod.PATCH, options);
                if (authentication) {
                    req.basicAuthentication("user", "pass");
                }
                if (headers != null) {
                    req.putHeaders(headers);
                }
                req.sendForm(
                        payload,
                        ar -> {
                            if (ar.succeeded()) {
                                future.complete(ar.result());
                            } else {
                                future.completeExceptionally(ar.cause());
                            }
                        });
                if (future.get().statusCode() == 308) {
                    return patch(
                            future.get().getHeader("Location"), true, headers, payload, timeout);
                }
                return future.get(timeout, TimeUnit.SECONDS);
            }
        }
    }
}
