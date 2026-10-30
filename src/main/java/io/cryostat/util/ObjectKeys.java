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

import org.apache.commons.lang3.tuple.Pair;

/**
 * Builds and parses the S3 object keys under which Cryostat stores archived blob data (archived
 * recordings, thread dumps, heap dumps, unified logs). Keys have the form {@code
 * {jvmId}/{filename}}, where the filename component may itself contain further {@code /}
 * characters. All code that composes or decomposes such keys should go through this class so that
 * the layout is defined in a single place.
 */
public final class ObjectKeys {

    private static final String SEPARATOR = "/";

    private ObjectKeys() {}

    /** Compose the object key for the given JVM ID and filename. */
    public static String join(String jvmId, String filename) {
        return (jvmId + SEPARATOR + filename).strip();
    }

    /** Compose the object key for a (JVM ID, filename) pair as produced by {@link #split}. */
    public static String join(Pair<String, String> key) {
        return join(key.getKey(), key.getValue());
    }

    /**
     * Decompose an object key into its JVM ID and filename. Only the first separator is
     * significant, so any further slashes remain part of the filename.
     *
     * @return a pair of the JVM ID (key) and the complete filename (value)
     * @throws IllegalArgumentException if the key contains no separator
     */
    public static Pair<String, String> split(String key) {
        String[] parts = key.split(SEPARATOR, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException();
        }
        return Pair.of(parts[0], parts[1]);
    }
}
