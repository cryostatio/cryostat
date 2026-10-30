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
package io.cryostat.security.rbac;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.http.Method;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for tests that exercise REST authorization in {@code OPENSHIFT} RBAC mode without a cluster.
 *
 * <p>Only the Kubernetes client beneath {@link SsarClientCache} is faked. The request
 * authentication, {@link PermissionMapper}, {@link SsarAuthorizer} and {@link SsarDecisionCache}
 * are the real implementations. {@code read}, {@code write} and {@code delete} permissions are
 * mapped to the distinct Kubernetes verbs {@code get}, {@code update} and {@code delete}, so the
 * recorded SSAR requests reveal which permission was asked about.
 *
 * <p>Subclasses share {@link OpenshiftProfile} so that the Quarkus application is started once for
 * all of them.
 */
public abstract class RbacSsarTestBase {

    /** Allowed for {@code get} and {@code update}, but not {@code delete}. */
    protected static final String READER_TOKEN = "reader-token";

    /** Allowed for {@code get}, {@code update} and {@code delete}. */
    protected static final String ADMIN_TOKEN = "admin-token";

    private static final Map<String, Set<String>> ALLOWED_K8S_VERBS =
            Map.of(
                    READER_TOKEN, Set.of("get", "update"),
                    ADMIN_TOKEN, Set.of("get", "update", "delete"));

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

    @InjectMock protected SsarClientCache ssarClientCache;
    @Inject protected SsarDecisionCache ssarDecisionCache;

    /** Kubernetes verbs of every SSAR request made since the start of the current test. */
    protected final List<String> ssarVerbs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpSsarFake() {
        ssarDecisionCache.invalidateAll();
        ssarVerbs.clear();
        when(ssarClientCache.withClient(any(), any()))
                .thenAnswer(
                        invocation -> {
                            String token = invocation.getArgument(0);
                            Function<KubernetesClient, Object> fn = invocation.getArgument(1);
                            return fn.apply(fakeClientFor(token));
                        });
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

    /** Sends a request as a user-path caller carrying the given access token. */
    protected Response request(Method method, String path, String token) {
        return given().basePath("/")
                .header(ProxyHeaders.FORWARDED_USER, "tester")
                .header(ProxyHeaders.FORWARDED_TOKEN, token)
                .when()
                .request(method, path)
                .then()
                .extract()
                .response();
    }
}
