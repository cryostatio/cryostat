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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class LongRunningRequestGeneratorTest {

    @Test
    void sanitizeTagPassesThroughAsciiAlphanumericHyphenUnderscore() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("my-app_v1"), is("my-app_v1"));
    }

    @Test
    void sanitizeTagReplacesForwardSlashWithUnderscore() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("a/b"), is("a_b"));
    }

    @Test
    void sanitizeTagReplacesBackslashWithUnderscore() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("a\\b"), is("a_b"));
    }

    @Test
    void sanitizeTagReplacesDotsAndColonsWithUnderscore() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("app.host:8080"), is("app_host_8080"));
    }

    @Test
    void sanitizeTagReplacesPathTraversalSequence() {
        assertThat(
                LongRunningRequestGenerator.sanitizeTag("../../etc/passwd"),
                is("______etc_passwd"));
    }

    @Test
    void sanitizeTagPreservesNonEnglishLetters() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("héros_αβγ"), is("héros_αβγ"));
    }

    @Test
    void sanitizeTagPreservesNonAsciiDigits() {
        assertThat(LongRunningRequestGenerator.sanitizeTag("tag123"), is("tag123"));
    }
}
