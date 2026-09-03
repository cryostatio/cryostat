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
package io.cryostat.util;

import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.http.HttpServerResponse;

public final class ResponseDispatch {

    /**
     * Registers handlers on {@code response} so that {@code action} is called exactly once when the
     * response finishes writing ({@code endHandler}) <em>or</em> when the underlying connection
     * closes before the response is fully sent ({@code closeHandler}). The {@link AtomicBoolean}
     * guard ensures only one of the two handlers executes the action.
     */
    public static void onComplete(HttpServerResponse response, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable once =
                () -> {
                    if (fired.compareAndSet(false, true)) {
                        action.run();
                    }
                };
        response.endHandler((e) -> once.run());
        response.closeHandler((e) -> once.run());
    }
}
