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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Method;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a programmatic {@code UserAuthorizer.assertAuthorized} call inside a REST endpoint
 * is enforced in {@code OPENSHIFT} mode and delegates to the SSAR layer, using {@code
 * activerecordings:delete}.
 *
 * <p>{@code POST /api/v4.1/targets/{id}/reports} requires only read and write permissions through
 * {@code @PermissionsAllowed}, and additionally asserts {@code activerecordings:delete} when {@code
 * clean=true}. The reader token satisfies the annotation but not the delete assertion, so a 403 can
 * only come from the programmatic guard. The target does not exist, so a request that passes
 * authorization fails later with a non-403 status.
 */
@QuarkusTest
@TestProfile(RbacSsarTestBase.OpenshiftProfile.class)
class RestAssertAuthorizedTest extends RbacSsarTestBase {

    private static final String ANALYZE_PATH = "/api/v4.1/targets/999999/reports";

    @Test
    void endpointIsRejectedWhenSsarDeniesAssertedPermission() {
        var response = request(Method.POST, ANALYZE_PATH + "?clean=true", READER_TOKEN);

        assertThat(response.statusCode(), equalTo(403));
        assertThat(ssarVerbs, hasItem("delete"));
    }

    @Test
    void endpointIsPermittedWhenSsarAllowsAssertedPermission() {
        var response = request(Method.POST, ANALYZE_PATH + "?clean=true", ADMIN_TOKEN);

        assertThat(response.statusCode(), equalTo(404));
        assertThat(ssarVerbs, hasItem("delete"));
    }

    @Test
    void assertionIsSkippedWhenNotRequired() {
        var response = request(Method.POST, ANALYZE_PATH + "?clean=false", READER_TOKEN);

        assertThat(response.statusCode(), equalTo(404));
        assertThat(ssarVerbs, not(hasItem("delete")));
    }
}
