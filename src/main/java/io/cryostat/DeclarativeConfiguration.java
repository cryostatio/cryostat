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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DeclarativeConfiguration {

    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.DECLARATIVE_CONFIG_RESOLVE_SYMLINKS)
    boolean resolveSymlinks;

    public SortedSet<Path> walk(Path dir) throws IOException {
        logger.debugv("Walking directory {0}", dir);
        if (!Files.exists(dir)) {
            return Collections.emptySortedSet();
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException(String.format("Location %s is not a directory", dir));
        }
        if (!Files.isReadable(dir)) {
            throw new IOException(String.format("Location %s is not readable", dir));
        }
        if (!Files.isExecutable(dir)) {
            throw new IOException(String.format("Location %s is not executable", dir));
        }
        Stream<Path> paths = Files.walk(dir).filter(Files::isReadable);
        if (resolveSymlinks) {
            // attempt to resolve symlinks, which will be deduplicated later
            paths =
                    paths.map(
                                    p -> {
                                        try {
                                            return p.toRealPath();
                                        } catch (IOException e) {
                                            logger.error(e);
                                            return null;
                                        }
                                    })
                            .filter(Objects::nonNull);
        } else {
            // filter out and ignore symlinks entirely
            paths = paths.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS));
        }
        paths =
                paths.map(Path::normalize)
                        .peek(p -> logger.tracev("found declarative configuration file {0}", p));
        return new TreeSet<>(paths.toList());
    }
}
