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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BuildInfo {

    private static final String RESOURCE_LOCATION = "META-INF/gitinfo";

    @Inject @JsonIgnore Logger logger;

    private final GitInfo gitinfo = new GitInfo();

    @JsonProperty("git")
    public GitInfo getGitInfo() {
        return gitinfo;
    }

    public class GitInfo {
        public String getHash() {
            try (BufferedReader br =
                    new BufferedReader(
                            new InputStreamReader(
                                    Thread.currentThread()
                                            .getContextClassLoader()
                                            .getResourceAsStream(RESOURCE_LOCATION),
                                    StandardCharsets.UTF_8))) {
                return br.lines()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                String.format(
                                                        "Resource file %s is empty",
                                                        RESOURCE_LOCATION)))
                        .trim();
            } catch (Exception e) {
                logger.warn("Version retrieval exception", e);
                return "unknown";
            }
        }
    }
}
