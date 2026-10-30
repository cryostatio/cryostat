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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.cryostat.recordings.RecordingHelper;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Method;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @PermissionsAllowed} on a REST endpoint is enforced in {@code OPENSHIFT}
 * mode and delegates to the SSAR layer, using {@code archivedrecordings:delete}.
 */
@QuarkusTest
@TestProfile(RbacSsarTestBase.OpenshiftProfile.class)
class RestPermissionsAllowedTest extends RbacSsarTestBase {

    private static final String DELETE_PATH = "/api/v4/recordings/test.jfr";

    @InjectMock RecordingHelper recordingHelper;

    @Test
    void endpointIsRejectedWhenSsarDeniesPermission() throws Exception {
        var response = request(Method.DELETE, DELETE_PATH, READER_TOKEN);

        assertThat(response.statusCode(), equalTo(403));
        assertThat(ssarVerbs, hasItem("delete"));
        verify(recordingHelper, never()).deleteArchivedRecording(any(), any());
    }

    @Test
    void endpointIsPermittedWhenSsarAllowsPermission() throws Exception {
        var response = request(Method.DELETE, DELETE_PATH, ADMIN_TOKEN);

        assertThat(response.statusCode(), equalTo(204));
        assertThat(ssarVerbs, hasItem("delete"));
        verify(recordingHelper).deleteArchivedRecording("uploads", "test.jfr");
    }

    @Test
    void endpointWithoutDeleteVerbNeverAsksAboutDelete() {
        var response = request(Method.GET, "/api/v4/recordings", READER_TOKEN);

        assertThat(response.statusCode(), not(equalTo(403)));
        assertThat(ssarVerbs, not(hasItem("delete")));
    }
}
