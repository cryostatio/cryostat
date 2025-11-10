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

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class SemVerTest {

    @ParameterizedTest
    @CsvSource({
        "0.0.0, 0, 0, 0",
        "1.2.3, 1, 2, 3",
        "99.99.99, 99, 99, 99",
    })
    void testSimple(String s, int major, int minor, int patch) {
        SemVer sv = SemVer.parse(s);
        MatcherAssert.assertThat(sv.major(), Matchers.equalTo(major));
        MatcherAssert.assertThat(sv.minor(), Matchers.equalTo(minor));
        MatcherAssert.assertThat(sv.patch(), Matchers.equalTo(patch));
        MatcherAssert.assertThat(sv.prerelease(), Matchers.nullValue());
        MatcherAssert.assertThat(sv.buildmeta(), Matchers.nullValue());
    }

    @ParameterizedTest
    @CsvSource({
        "0.0.0-a, 0, 0, 0, a",
        "1.2.3-pre, 1, 2, 3, pre",
        "99.99.99-snapshot, 99, 99, 99, snapshot",
    })
    void testPrerelease(String s, int major, int minor, int patch, String prerelease) {
        SemVer sv = SemVer.parse(s);
        MatcherAssert.assertThat(sv.major(), Matchers.equalTo(major));
        MatcherAssert.assertThat(sv.minor(), Matchers.equalTo(minor));
        MatcherAssert.assertThat(sv.patch(), Matchers.equalTo(patch));
        MatcherAssert.assertThat(sv.prerelease(), Matchers.equalTo(prerelease));
        MatcherAssert.assertThat(sv.buildmeta(), Matchers.nullValue());
    }

    @ParameterizedTest
    @CsvSource({
        "1.2.3-a+b, 1, 2, 3, a, b",
        "1.2.3-some-words+build-info, 1, 2, 3, some-words, build-info",
    })
    void testFull(String s, int major, int minor, int patch, String prerelease, String buildmeta) {
        SemVer sv = SemVer.parse(s);
        MatcherAssert.assertThat(sv.major(), Matchers.equalTo(major));
        MatcherAssert.assertThat(sv.minor(), Matchers.equalTo(minor));
        MatcherAssert.assertThat(sv.patch(), Matchers.equalTo(patch));
        MatcherAssert.assertThat(sv.prerelease(), Matchers.equalTo(prerelease));
        MatcherAssert.assertThat(sv.buildmeta(), Matchers.equalTo(buildmeta));
    }

    @ParameterizedTest
    @CsvSource({
        "1.2.3.vendor-0001, 1, 2, 3, vendor-0001,",
        "1.2.3.vendor-0001+patch2, 1, 2, 3, vendor-0001, patch2",
    })
    void testVendoredBuild(
            String s, int major, int minor, int patch, String prerelease, String buildmeta) {
        SemVer sv = SemVer.parse(s);
        MatcherAssert.assertThat(sv.major(), Matchers.equalTo(major));
        MatcherAssert.assertThat(sv.minor(), Matchers.equalTo(minor));
        MatcherAssert.assertThat(sv.patch(), Matchers.equalTo(patch));
        MatcherAssert.assertThat(sv.prerelease(), Matchers.equalTo(prerelease));
        MatcherAssert.assertThat(sv.buildmeta(), Matchers.equalTo(buildmeta));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1.0.0", "a.b.c", "1.2", "1", "1.2.3+a+b", ".1.2"})
    void testInvalid(String s) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SemVer.parse(s));
    }
}
