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

package io.cryostat.recordings;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.targets.Target;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.SchedulerException;

@QuarkusTest
@TestHTTPEndpoint(RecordingSync.class)
public class RecordingSyncTest extends AbstractTransactionalTestBase {

    @InjectMock RecordingHelper recordingHelper;

    /**
     * Pauses background jobs and supplies an empty recording list for test cleanup.
     *
     * @throws SchedulerException if the scheduler cannot be paused or cleared
     */
    @BeforeEach
    void setup() throws SchedulerException {
        shutdownScheduler();
        when(recordingHelper.listActiveRecordings(any(Target.class))).thenReturn(List.of());
    }

    /** Verifies that a missing target returns 404 without invoking synchronization. */
    @Test
    void testSyncOnMissingTarget() {
        given().pathParam("targetId", Integer.MAX_VALUE).when().post().then().statusCode(404);

        verify(recordingHelper, org.mockito.Mockito.never())
                .syncActiveRecordings(any(Target.class));
    }

    /** Verifies that a successful request returns 204 and synchronizes the requested target. */
    @Test
    void testSync() {
        int targetId = defineSelfCustomTarget();
        clearInvocations(recordingHelper);

        given().pathParam("targetId", targetId).when().post().then().statusCode(204);

        ArgumentCaptor<Target> targetCaptor = ArgumentCaptor.forClass(Target.class);
        verify(recordingHelper).syncActiveRecordings(targetCaptor.capture());
        assertEquals((long) targetId, targetCaptor.getValue().id.longValue());
    }
}
