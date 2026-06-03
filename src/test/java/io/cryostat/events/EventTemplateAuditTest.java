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
package io.cryostat.events;

import static io.restassured.RestAssured.given;

import io.cryostat.audit.AuditTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EventTemplateAuditTest.class)
@TestHTTPEndpoint(EventTemplates.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class EventTemplateAuditTest extends AuditTestBase {

    @Inject EntityManager em;

    @Test
    public void testEventTemplateUploadCreatesAuditLog() {
        long startTime = System.currentTimeMillis();

        var templateName =
                given().contentType(ContentType.MULTIPART)
                        .multiPart(
                                "template",
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <configuration version="2.0" label="TestTemplate" description="Test Template" provider="TestProvider">
                                </configuration>
                                """)
                        .log()
                        .all()
                        .when()
                        .post()
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .jsonPath()
                        .getString("name");

        long endTime = System.currentTimeMillis();

        Assertions.assertNotNull(templateName, "Template name should be returned");

        EventTemplate template =
                EventTemplate.<EventTemplate>find(
                                "templateName = ?1 and templateType = ?2", templateName, "CUSTOM")
                        .firstResult();
        Assertions.assertNotNull(template, "EventTemplate entity should be created");
        Assertions.assertEquals(templateName, template.templateName);
        Assertions.assertEquals("CUSTOM", template.templateType);
        Assertions.assertEquals("TestProvider", template.provider);

        AuditReader auditReader = AuditReaderFactory.get(em);
        var revisions = auditReader.getRevisions(EventTemplate.class, template.id);
        Assertions.assertFalse(revisions.isEmpty(), "Should have at least one audit revision");

        Integer revisionNumber =
                given().basePath("/")
                        .log()
                        .all()
                        .queryParam("startTime", startTime)
                        .queryParam("endTime", endTime)
                        .queryParam("pageSize", 10)
                        .when()
                        .get("/api/beta/audit/revisions")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .body("revisions", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))
                        .extract()
                        .path("revisions[0].rev");

        Assertions.assertNotNull(revisionNumber, "Should have at least one revision");

        given().basePath("/")
                .log()
                .all()
                .when()
                .get("/api/beta/audit/revisions/{rev}", revisionNumber)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .body("rev", org.hamcrest.Matchers.equalTo(revisionNumber))
                .body("entities", org.hamcrest.Matchers.notNullValue())
                .body("entities.EventTemplate", org.hamcrest.Matchers.notNullValue())
                .body(
                        "entities.EventTemplate",
                        org.hamcrest.Matchers.instanceOf(java.util.List.class))
                .body(
                        "entities.EventTemplate[0].templateName",
                        org.hamcrest.Matchers.equalTo(templateName))
                .body(
                        "entities.EventTemplate[0].templateType",
                        org.hamcrest.Matchers.equalTo("CUSTOM"))
                .body("entities.EventTemplate[0].revtype", org.hamcrest.Matchers.equalTo(0));

        given().log().all().when().delete(templateName);
    }

    @Test
    public void testEventTemplateDeletionCreatesAuditLog() {
        var templateName =
                given().contentType(ContentType.MULTIPART)
                        .multiPart(
                                "template",
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <configuration version="2.0" label="DeleteTest" description="Delete Test Template" provider="TestProvider">
                                </configuration>
                                """)
                        .log()
                        .all()
                        .when()
                        .post()
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .jsonPath()
                        .getString("name");

        EventTemplate template =
                EventTemplate.<EventTemplate>find(
                                "templateName = ?1 and templateType = ?2", templateName, "CUSTOM")
                        .firstResult();
        Assertions.assertNotNull(template, "EventTemplate entity should exist before deletion");
        Long templateId = template.id;

        long startTime = System.currentTimeMillis();

        given().log()
                .all()
                .when()
                .delete(templateName)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        long endTime = System.currentTimeMillis();

        EventTemplate deletedTemplate =
                EventTemplate.<EventTemplate>find(
                                "templateName = ?1 and templateType = ?2", templateName, "CUSTOM")
                        .firstResult();
        Assertions.assertNull(deletedTemplate, "EventTemplate entity should be deleted");

        AuditReader auditReader = AuditReaderFactory.get(em);
        var revisions = auditReader.getRevisions(EventTemplate.class, templateId);
        Assertions.assertTrue(
                revisions.size() >= 2,
                "Should have at least two audit revisions (create + delete), but got: "
                        + revisions.size());

        Integer revisionNumber =
                given().basePath("/")
                        .log()
                        .all()
                        .queryParam("startTime", startTime)
                        .queryParam("endTime", endTime)
                        .queryParam("pageSize", 10)
                        .when()
                        .get("/api/beta/audit/revisions")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .body("revisions", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))
                        .extract()
                        .path("revisions[0].rev");

        given().basePath("/")
                .log()
                .all()
                .when()
                .get("/api/beta/audit/revisions/{rev}", revisionNumber)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .body("rev", org.hamcrest.Matchers.equalTo(revisionNumber))
                .body("entities", org.hamcrest.Matchers.notNullValue())
                .body("entities.EventTemplate", org.hamcrest.Matchers.notNullValue())
                .body(
                        "entities.EventTemplate[0].id",
                        org.hamcrest.Matchers.equalTo(templateId.intValue()))
                .body("entities.EventTemplate[0].revtype", org.hamcrest.Matchers.equalTo(2));
    }

    @Test
    public void testMultipleEventTemplateUploadsCreateMultipleAuditEntries() {
        for (int i = 0; i < 3; i++) {
            given().contentType(ContentType.MULTIPART)
                    .multiPart(
                            "template",
                            String.format(
                                    """
                                    <?xml version="1.0" encoding="UTF-8"?>
                                    <configuration version="2.0" label="Template%d" description="Test Template %d" provider="TestProvider">
                                    </configuration>
                                    """,
                                    i, i))
                    .log()
                    .all()
                    .when()
                    .post()
                    .then()
                    .log()
                    .all()
                    .assertThat()
                    .statusCode(201);
        }

        long count = EventTemplate.count("templateType", "CUSTOM");
        Assertions.assertEquals(3, count, "Expected three EventTemplate entities to be created");

        for (int i = 0; i < 3; i++) {
            given().log().all().when().delete("Template" + i);
        }
    }
}
