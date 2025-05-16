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

import org.openjdk.jmc.common.security.SecurityManagerFactory;

import io.cryostat.core.CryostatCore;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main application entrypoint. Perform any required early initialization tasks, then kick off the
 * webserver.
 */
@QuarkusMain
public class Cryostat {

    public static void main(String... args) {
        Quarkus.run(Launcher.class, args);
    }

    public static class Launcher implements QuarkusApplication {
        @Override
        public int run(String... args) throws Exception {
            CryostatCore.initialize();
            SecurityManagerFactory.setDefaultSecurityManager(
                    new io.cryostat.core.jmc.SecurityManager());
            Quarkus.waitForExit();
            return 0;
        }
    }
}
