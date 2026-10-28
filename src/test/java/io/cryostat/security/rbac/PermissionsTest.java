package io.cryostat.security.rbac;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.cryostat.AbstractTransactionalTestBase;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@TestHTTPEndpoint(PermissionCheck.class)
public class PermissionsTest extends AbstractTransactionalTestBase {
    
    @Test
    public void testList() {
        Response response =
                given()
                .basePath("/")
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
                given()
                .basePath("/")
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
