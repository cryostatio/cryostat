package io.cryostat;

import static io.cryostat.TestUtils.givenBasicAuth;

import javax.swing.text.AbstractDocument.Content;

import org.apache.http.entity.ContentType;
import org.eclipse.microprofile.openapi.models.media.XML;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.vertx.core.json.JsonObject;
import jakarta.transaction.Transactional;

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
        givenBasicAuth().body().contentType(ContentType.APPLICATION_XML).post().then().statusCode(200);
    }
}
