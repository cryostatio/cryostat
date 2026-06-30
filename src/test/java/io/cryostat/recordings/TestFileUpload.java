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
package io.cryostat.recordings;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.resteasy.reactive.multipart.FileUpload;

class TestFileUpload implements FileUpload {
    private final String fileName;
    private final Path path;

    TestFileUpload(String fileName, Path path) {
        this.fileName = fileName;
        this.path = path;
    }

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public Path filePath() {
        return path;
    }

    @Override
    public String fileName() {
        return fileName;
    }

    @Override
    public long size() {
        try {
            return Files.size(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String contentType() {
        return "application/octet-stream";
    }

    @Override
    public String charSet() {
        return "";
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return new MultivaluedHashMap<>();
    }
}
