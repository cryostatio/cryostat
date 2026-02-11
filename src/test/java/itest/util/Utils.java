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
package itest.util;

import io.cryostat.libcryostat.sys.Environment;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.file.FileSystem;

public class Utils {

    public static final int WEB_PORT;
    public static final String WEB_HOST;

    static {
        Environment env = new Environment();
        WEB_PORT = Integer.valueOf(env.getEnv("QUARKUS_HTTP_PORT", "8081"));
        WEB_HOST = "localhost";
    }

    private static final Vertx VERTX =
            Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));

    public static FileSystem getFileSystem() {
        return VERTX.fileSystem();
    }
}
