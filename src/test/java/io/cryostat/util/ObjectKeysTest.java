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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ObjectKeysTest {

    @ParameterizedTest
    @CsvSource({
        "jvm/file.jfr,jvm,file.jfr",
        "jvm/dir/file.jfr,jvm,dir/file.jfr",
        "jvm/a/b/c/file.jfr,jvm,a/b/c/file.jfr",
        "jvm/trailing/,jvm,trailing/"
    })
    void splitsAtFirstSeparatorOnly(String key, String jvmId, String filename) {
        assertEquals(Pair.of(jvmId, filename), ObjectKeys.split(key));
    }

    @Test
    void splitRejectsKeyWithoutSeparator() {
        assertThrows(IllegalArgumentException.class, () -> ObjectKeys.split("file.jfr"));
    }

    @Test
    void joinComposesKey() {
        assertEquals("jvm/file.jfr", ObjectKeys.join("jvm", "file.jfr"));
        assertEquals("jvm/dir/file.jfr", ObjectKeys.join("jvm", "dir/file.jfr"));
        assertEquals("jvm/dir/file.jfr", ObjectKeys.join(Pair.of("jvm", "dir/file.jfr")));
    }

    @ParameterizedTest
    @CsvSource({"jvm,file.jfr", "jvm,dir/file.jfr", "jvm,a/b/c/file.jfr"})
    void joinAndSplitRoundTrip(String jvmId, String filename) {
        assertEquals(Pair.of(jvmId, filename), ObjectKeys.split(ObjectKeys.join(jvmId, filename)));
    }
}
