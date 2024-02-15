package io.cryostat;

import static io.cryostat.TestUtils.givenBasicAuth;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;

import jakarta.transaction.Transactional;
import org.apache.http.entity.ContentType;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class CustomEventTemplate {

    Template template;

    static String TEMPLATE_NAME = "customEventTemplate";

    @BeforeEach
    public void setup() {
        template = new Template(TEMPLATE_NAME, "desc", "prov", TemplateType.CUSTOM);
    }

    @AfterEach
    @Transactional
    public void afterEach() {
        template.deleteAll();
    }

    @Test
    public void testCustomEventTemplate() {
        givenBasicAuth().get().then().statusCode(200);
    }

    @Test
    public void testCustom() {
        givenBasicAuth()
                .body()
                .contentType(ContentType.APPLICATION_XML)
                .post()
                .then()
                .statusCode(200);
    }
}
