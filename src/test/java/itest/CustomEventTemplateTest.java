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
package itest;

import io.quarkus.test.junit.DisabledOnIntegrationTest;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.multipart.MultipartForm;
import itest.bases.StandardSelfTest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisabledOnIntegrationTest("classpath resources are not loadable in integration test")
public class CustomEventTemplateTest extends StandardSelfTest {

    static final String INVALID_TEMPLATE_FILE_NAME = "invalidTemplate.xml";
    static final String TEMPLATE_FILE_NAME = "CustomEventTemplate.jfc";
    static final String TEMPLATE_NAME = "invalidTemplate";
    static final String MEDIA_TYPE = "application/xml";
    static final String REQ_URL = "/api/v1/templates";

    @Test
    public void shouldThrowIfTemplateUploadNameInvalid() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        try (var stream = classLoader.getResourceAsStream(INVALID_TEMPLATE_FILE_NAME)) {
            var buf = Buffer.buffer(stream.readAllBytes());
            MultipartForm form =
                    MultipartForm.create()
                            .binaryFileUpload(
                                    TEMPLATE_NAME, INVALID_TEMPLATE_FILE_NAME, buf, MEDIA_TYPE);

            HttpResponse<Buffer> resp =
                    webClient.extensions().post(REQ_URL, form, REQUEST_TIMEOUT_SECONDS);
            MatcherAssert.assertThat(resp.statusCode(), Matchers.equalTo(400));
        }
    }

    @Test
    public void shouldThrowWhenPostingInvalidTemplate() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        try (var stream = classLoader.getResourceAsStream(INVALID_TEMPLATE_FILE_NAME)) {
            var buf = Buffer.buffer(stream.readAllBytes());
            MultipartForm form =
                    MultipartForm.create()
                            .binaryFileUpload(
                                    TEMPLATE_NAME, INVALID_TEMPLATE_FILE_NAME, buf, MEDIA_TYPE);

            HttpResponse<Buffer> resp =
                    webClient.extensions().post(REQ_URL, form, REQUEST_TIMEOUT_SECONDS);
            MatcherAssert.assertThat(resp.statusCode(), Matchers.equalTo(400));
        }
    }

    @Test
    public void testDeleteRecordingThrowsOnNonExistentTemplate() throws Exception {
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .delete(
                                String.format("%s/%s", REQ_URL, INVALID_TEMPLATE_FILE_NAME),
                                REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(resp.statusCode(), Matchers.equalTo(404));
    }

    @Test
    public void testPostedTemplateNameIsSanitizedAndCanBeDeleted() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        try (var stream = classLoader.getResourceAsStream(TEMPLATE_FILE_NAME)) {
            var buf = Buffer.buffer(stream.readAllBytes());
            MultipartForm form =
                    MultipartForm.create()
                            .binaryFileUpload("template", TEMPLATE_FILE_NAME, buf, MEDIA_TYPE);
            HttpResponse<Buffer> postResp =
                    webClient.extensions().post(REQ_URL, form, REQUEST_TIMEOUT_SECONDS);
            MatcherAssert.assertThat(postResp.statusCode(), Matchers.equalTo(204));

            HttpResponse<Buffer> getResp =
                    webClient
                            .extensions()
                            .get(
                                    String.format(
                                            "/api/v1/targets/%s/templates",
                                            getSelfReferenceConnectUrlEncoded()),
                                    REQUEST_TIMEOUT_SECONDS);
            boolean foundSanitizedTemplate = false;
            for (Object o : getResp.bodyAsJsonArray()) {
                JsonObject json = (JsonObject) o;
                var name = json.getString("name");
                foundSanitizedTemplate =
                        foundSanitizedTemplate || name.equals("Custom_Event_Template");
            }
            Assertions.assertTrue(foundSanitizedTemplate);
        } finally {
            var delResp =
                    webClient
                            .extensions()
                            .delete(
                                    String.format(
                                            "%s%s",
                                            REQ_URL,
                                            URLEncodedUtils.formatSegments(
                                                    "Custom_Event_Template")),
                                    REQUEST_TIMEOUT_SECONDS);
            MatcherAssert.assertThat(delResp.statusCode(), Matchers.equalTo(204));
        }
    }
}
