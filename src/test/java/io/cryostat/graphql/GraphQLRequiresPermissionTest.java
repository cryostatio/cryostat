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
package io.cryostat.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.security.rbac.SsarClientCache;
import io.cryostat.security.rbac.SsarDecisionCache;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @RequiresPermission} on GraphQL resolvers is enforced in {@code OPENSHIFT}
 * RBAC mode and delegates to the SelfSubjectAccessReview (SSAR) layer.
 *
 * <p>No cluster is available, so only the Kubernetes client beneath {@link SsarClientCache} is
 * faked. Everything above it, including the GraphQL security hook, {@code PermissionMapper}, {@code
 * SsarAuthorizer} and {@link SsarDecisionCache}, is the real implementation. Each permission verb
 * is mapped to a distinct Kubernetes verb so that the SSAR requests reveal which permission the
 * hook asked about.
 */
@QuarkusTest
@TestProfile(GraphQLRequiresPermissionTest.OpenshiftProfile.class)
class GraphQLRequiresPermissionTest {

    static final String READER_TOKEN = "reader-token";
    static final String ADMIN_TOKEN = "admin-token";
    static final String NOBODY_TOKEN = "nobody-token";
    static final String HANGING_TOKEN = "hanging-token";

    static final Map<String, Set<String>> ALLOWED_K8S_VERBS =
            Map.of(
                    READER_TOKEN, Set.of("get"),
                    ADMIN_TOKEN, Set.of("get", "update", "delete"),
                    NOBODY_TOKEN, Set.of());

    private static final String LIST_QUERY =
            """
            query {
              threadDumps {
                data {
                  threadDumpId
                }
              }
            }
            """;

    private static final String DELETE_QUERY =
            """
            query {
              threadDumps {
                data {
                  doDelete {
                    threadDumpId
                  }
                }
              }
            }
            """;

    public static class OpenshiftProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.security.rbac.mode", "OPENSHIFT",
                    "cryostat.security.rbac.default-read-permission", "pods:get",
                    "cryostat.security.rbac.default-write-permission", "pods:update",
                    "cryostat.security.rbac.default-delete-permission", "pods:delete",
                    "cryostat.security.rbac.default-permission", "pods:get");
        }
    }

    @InjectMock SsarClientCache ssarClientCache;
    @InjectMock DiagnosticsHelper diagnosticsHelper;
    @Inject SsarDecisionCache ssarDecisionCache;

    private final List<String> ssarVerbs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ssarDecisionCache.invalidateAll();
        ssarVerbs.clear();

        when(ssarClientCache.withClient(any(), any()))
                .thenAnswer(
                        invocation -> {
                            String token = invocation.getArgument(0);
                            Function<KubernetesClient, Object> fn = invocation.getArgument(1);
                            return fn.apply(fakeClientFor(token));
                        });

        when(diagnosticsHelper.getThreadDumps((String) any()))
                .thenReturn(
                        List.of(
                                new ThreadDump(
                                        "jvm",
                                        "http://example/dump",
                                        "dump-1",
                                        1L,
                                        1L,
                                        Metadata.empty())));
    }

    @SuppressWarnings("unchecked")
    private KubernetesClient fakeClientFor(String token) {
        var client = mock(KubernetesClient.class);
        var authGroup = mock(AuthorizationAPIGroupDSL.class);
        var v1 = mock(V1AuthorizationAPIGroupDSL.class);
        var ssarOp = mock(InOutCreateable.class);
        when(client.authorization()).thenReturn(authGroup);
        when(authGroup.v1()).thenReturn(v1);
        when(v1.selfSubjectAccessReview()).thenReturn(ssarOp);
        when(ssarOp.create(any()))
                .thenAnswer(
                        invocation -> {
                            if (HANGING_TOKEN.equals(token)) {
                                Thread.sleep(Duration.ofSeconds(8));
                            }
                            SelfSubjectAccessReview request = invocation.getArgument(0);
                            String verb = request.getSpec().getResourceAttributes().getVerb();
                            ssarVerbs.add(verb);
                            var status = new SubjectAccessReviewStatus();
                            status.setAllowed(
                                    ALLOWED_K8S_VERBS.getOrDefault(token, Set.of()).contains(verb));
                            var review = new SelfSubjectAccessReview();
                            review.setStatus(status);
                            return review;
                        });
        return client;
    }

    private JsonObject post(String token, String query) {
        Response response =
                given().basePath("/")
                        .header("X-Forwarded-User", "tester")
                        .header("X-Forwarded-Access-Token", token)
                        .contentType(ContentType.JSON)
                        .body(Map.of("query", query))
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .extract()
                        .response();
        return new JsonObject(response.asString());
    }

    @Test
    void resolverIsRejectedWhenSsarDeniesPermission() {
        JsonObject body = post(NOBODY_TOKEN, LIST_QUERY);

        assertThat(body.getValue("errors"), notNullValue());
        assertThat(ssarVerbs, hasItem("get"));
    }

    @Test
    void resolverIsPermittedWhenSsarAllowsPermission() {
        JsonObject body = post(READER_TOKEN, LIST_QUERY);

        assertThat(body.getValue("errors"), nullValue());
        assertThat(ssarVerbs, not(empty()));
        assertThat(ssarVerbs, not(hasItem("delete")));
    }

    @Test
    void sourceFieldMutationIsRejectedWithoutItsOwnPermission() throws Exception {
        JsonObject body = post(READER_TOKEN, DELETE_QUERY);

        assertThat(body.getValue("errors"), notNullValue());
        assertThat(ssarVerbs, hasItem("delete"));
        verify(diagnosticsHelper, never()).deleteThreadDump(any(), any());
    }

    @Test
    void sourceFieldMutationIsPermittedWithItsOwnPermission() throws Exception {
        JsonObject body = post(ADMIN_TOKEN, DELETE_QUERY);

        assertThat(body.getValue("errors"), nullValue());
        assertThat(ssarVerbs, hasItem("delete"));
        verify(diagnosticsHelper).deleteThreadDump("jvm", "dump-1");
    }

    @Test
    void resolverIsRejectedWhenSsarDoesNotRespondInTime() throws Exception {
        long start = System.nanoTime();
        JsonObject body = post(HANGING_TOKEN, DELETE_QUERY);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(body.getValue("errors"), notNullValue());
        assertThat(elapsed, lessThan(Duration.ofSeconds(8)));
        verify(diagnosticsHelper, never()).deleteThreadDump(any(), any());
    }

    @Test
    void eachRequiredPermissionIsCheckedAgainstTheSsarLayer() {
        post(ADMIN_TOKEN, DELETE_QUERY);

        assertThat(ssarVerbs.stream().distinct().sorted().toList(), contains("delete", "get"));
    }
}
