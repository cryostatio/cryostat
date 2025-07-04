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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.openjdk.jmc.common.security.SecurityManagerFactory;

import io.cryostat.core.CryostatCore;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.smallrye.mutiny.infrastructure.Infrastructure;

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

            var i = new AtomicInteger(1);
            ExecutorService emissionPool =
                    Executors.newFixedThreadPool(
                            8,
                            r -> {
                                Thread thread = new Thread(r);
                                thread.setName(
                                        String.format(
                                                "emission-pool-thread-%d", i.getAndIncrement()));
                                return thread;
                            });
            Infrastructure.setDefaultExecutor(emissionPool);
            Quarkus.waitForExit();
            return 0;
        }
    }
}
