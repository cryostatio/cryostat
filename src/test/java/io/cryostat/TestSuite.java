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
package io.cryostat;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class TestSuite implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String enabled = System.getProperty("rest-assured.logging.enabled");
        if (enabled == null || Boolean.valueOf(enabled)) {
            RestAssured.filters(
                    new RequestLoggingFilter(), new ResponseLoggingFilter(), new FooFilter());
        } else {
            RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        }
    }

    public static class FooFilter implements Filter {

        @Override
        public Response filter(
                FilterableRequestSpecification requestSpec,
                FilterableResponseSpecification responseSpec,
                FilterContext ctx) {
            return ctx.next(requestSpec, responseSpec);
        }
    }
}
