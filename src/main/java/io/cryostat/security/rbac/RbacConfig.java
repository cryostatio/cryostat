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
package io.cryostat.security.rbac;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "cryostat.security.rbac")
public interface RbacConfig {

    @WithName("mode")
    @WithDefault("PERMISSIVE")
    RbacMode mode();

    /**
     * Map of Cryostat permission names to Kubernetes resource/verb strings. Keys use dot-separated
     * notation matching the quoted property key style, e.g. {@code activerecordings.read}. Values
     * follow {@code [resource(/subresource)]:verb} format, e.g. {@code pods/exec:create}.
     */
    @WithName("permissions")
    Map<String, String> permissions();

    /**
     * Fallback Kubernetes resource/verb applied when a requested permission name has no explicit
     * entry in the {@code permissions} map. Follows the same {@code [resource(/subresource)]:verb}
     * format, e.g. {@code pods/exec:create}. When absent, an unmapped permission is denied.
     */
    @WithName("default-permission")
    Optional<String> defaultPermission();

    CacheConfig cache();

    interface CacheConfig {
        @WithName("expire-after-access")
        @WithDefault("5m")
        Duration expireAfterAccess();

        @WithName("maximum-size")
        @WithDefault("1000")
        long maximumSize();
    }
}
