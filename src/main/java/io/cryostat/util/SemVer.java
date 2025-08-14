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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemVer(int major, int minor, int patch, String prerelease, String buildmeta) {

    static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "^(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)(?:-(?<prerelease>(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+(?<buildmeta>[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    public static SemVer parse(String s) {
        Matcher matcher = VERSION_PATTERN.matcher(s);
        if (!matcher.find()) {
            throw new IllegalArgumentException(s);
        }
        String major = matcher.group("major");
        String minor = matcher.group("minor");
        String patch = matcher.group("patch");
        String prerelease = matcher.group("prerelease");
        String buildmeta = matcher.group("buildmeta");
        return new SemVer(
                Integer.parseInt(major),
                Integer.parseInt(minor),
                Integer.parseInt(patch),
                prerelease,
                buildmeta);
    }
}
