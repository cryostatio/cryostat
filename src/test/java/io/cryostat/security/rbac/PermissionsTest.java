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
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(PermissionCheck.class)
public class PermissionsTest extends AbstractTransactionalTestBase {

    @Test
    public void testList() {
        Response response =
                given().basePath("/")
                        .when()
                        .get("/api/beta/auth/permissions")
                        .then()
                        .extract()
                        .response();
        JsonArray permissions = new JsonArray(response.body().asString());
        assertEquals(permissions.size(), 14);
    }

    @Test
    public void testCheckValid() {
        Response response =
                given().basePath("/")
                        .formParam("permission", "targets:read")
                        .when()
                        .post("/api/beta/auth/check")
                        .then()
                        .extract()
                        .response();
        JsonObject permissions = new JsonObject(response.body().asString());
        assertEquals(permissions.getString("authorized"), "true");
    }
}
