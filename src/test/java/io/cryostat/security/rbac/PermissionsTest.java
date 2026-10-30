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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.cryostat.AbstractTransactionalTestBase;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(PermissionCheck.class)
public class PermissionsTest extends AbstractTransactionalTestBase {

    // See PermissionAnnotationArchTest for how this is generated
    private static Set<String> PERMISSION_SET;

    @BeforeAll
    public static void generatePermissionSet() {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("io.cryostat");

        Set<String> allPermissions =
                classes.stream()
                        .flatMap(c -> c.getMethods().stream())
                        .flatMap(m -> m.getAnnotations().stream())
                        .filter(a -> a.getRawType().isEquivalentTo(PermissionsAllowed.class))
                        .flatMap(
                                a -> {
                                    Object val =
                                            a.get("value")
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "@PermissionsAllowed"
                                                                                    + " missing"
                                                                                    + " value"));
                                    return Arrays.stream((String[]) val);
                                })
                        .collect(Collectors.toSet());
        PERMISSION_SET = allPermissions;
    }

    @Test
    public void testList() {
        Response response =
                given().basePath("/")
                        .when()
                        .get("/api/beta/auth/permissions")
                        .then()
                        .extract()
                        .response();
        String resp = response.body().asString();
        JsonArray array = new JsonArray(resp);
        Set<Map<String, String>> expected = new HashSet<>();
        for (String s : PERMISSION_SET) {
            var tmp = s.strip().split(":");
            expected.add(Map.of("resource", tmp[0], "action", tmp[1]));
        }
        for (Map<String, String> m : expected) {
            assertEquals(array.contains(m), true);
        }
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
        assertEquals(true, permissions.getBoolean("authorized"));
    }

    @Test
    public void testMappings() {
        Response response =
                given().basePath("/")
                        .when()
                        .post("/api/beta/auth/mapping")
                        .then()
                        .extract()
                        .response();
        String resp = response.body().asString();
        JsonArray array = new JsonArray(resp);
        List<String> permissions = new ArrayList<String>();
        for (Object o : array) {
            var tmp = (JsonObject) o;
            assertEquals(tmp.getJsonObject("response").getBoolean("authorized"), true);
            var permission =
                    tmp.getJsonObject("permission").getString("resource")
                            + ":"
                            + tmp.getJsonObject("permission").getString("action");
            permissions.add(permission);
            assertEquals(PERMISSION_SET.contains(permission), true);
        }
        assertEquals(permissions.size(), PERMISSION_SET.size());
        assertEquals(PERMISSION_SET.containsAll(permissions), true);
    }
}
