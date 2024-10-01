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
package itest.util.http;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.vertx.core.json.JsonObject;
import itest.bases.StandardSelfTest;
import itest.util.Utils;
import itest.util.Utils.TestWebClient;

public class JvmIdWebRequest {
    public static final TestWebClient webClient = Utils.getWebClient();

    public static String jvmIdRequest(long id)
            throws InterruptedException, ExecutionException, TimeoutException {
        return webClient
                .extensions()
                .get(
                        String.format("/api/v4/targets/%d", id),
                        StandardSelfTest.REQUEST_TIMEOUT_SECONDS)
                .bodyAsJsonObject()
                .getString("jvmId");
    }

    public static String jvmIdRequest(String connectUrl)
            throws InterruptedException, ExecutionException, TimeoutException {
        return webClient
                .extensions()
                .get("/api/v4/targets", StandardSelfTest.REQUEST_TIMEOUT_SECONDS)
                .bodyAsJsonArray()
                .stream()
                .map(o -> (JsonObject) o)
                .filter(o -> Objects.equals(connectUrl, o.getString("connectUrl")))
                .findFirst()
                .map(o -> o.getString("jvmId"))
                .orElseThrow();
    }
}
