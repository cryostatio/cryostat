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
package io.cryostat.triggers;

import static io.restassured.RestAssured.given;

import java.util.UUID;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(SmartTriggers.class)
public class SmartTriggersTest extends AbstractTransactionalTestBase {

    // Smart Triggers are only supported for agent targets,
    // all of these are expected to be Bad Requests (400)

    @Test
    public void testList() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testListInvalid() {
        given().log()
                .all()
                .when()
                .pathParam("jvmId", UUID.randomUUID())
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testDeleteInvalid() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("id", "foo")
                .delete("/{id}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testPost() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .formParam("definition", "[foo]~bar")
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }
}
