package io.cryostat;

import static io.cryostat.TestUtils.givenBasicAuth;

import org.eclipse.microprofile.openapi.models.media.XML;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class CustomEventTemplate {

    @BeforeEach
    public void setup() {

    }

    @AfterEach
    public void afterEach() {

    }

    @Test
    public void testCustomEventTemplate() {
        givenBasicAuth().get().contentType(XML).then().post().statusCode(200);
    }
}
