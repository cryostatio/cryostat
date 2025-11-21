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
package io.cryostat.diagnostics;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.diagnostic.Diagnostics;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(S3StorageResource.class)
@TestHTTPEndpoint(Diagnostics.class)
public class HeapDumpsAllArchivesTest extends AbstractTransactionalTestBase {

    @Inject Logger logger;

    @Test
    public void testListNone() {
        given().log()
                .all()
                .when()
                .get("fs/heapdumps")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    @Disabled("TODO - this requires the Cryostat Agent installed on the target application")
    public void testCreateListAndDelete()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {}
}
