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
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "cryostat.security.rbac")
public interface RbacConfig {

    @WithName("mode")
    RbacMode mode();

    /**
     * Map of Cryostat permission names to Kubernetes resource/verb strings. Keys use dot-separated
     * notation matching the quoted property key style, e.g. {@code activerecordings.read}. Values
     * follow {@code [resource(/subresource)]:verb} format, e.g. {@code pods/exec:create}.
     */
    @WithName("permissions")
    Map<String, String> permissions();

    /**
     * Fallback Kubernetes resource/verb applied when a requested {@code *:read} permission name has
     * no explicit entry in the {@code permissions} map, and is checked before {@link
     * #defaultPermission()}. Follows the same {@code [resource(/subresource)]:verb} format, e.g.
     * {@code pods:get}.
     */
    @WithName("default-read-permission")
    Optional<String> defaultReadPermission();

    /**
     * Fallback Kubernetes resource/verb applied when a requested {@code *:write} permission name
     * has no explicit entry in the {@code permissions} map, and is checked before {@link
     * #defaultPermission()}. Follows the same {@code [resource(/subresource)]:verb} format, e.g.
     * {@code pods/exec:create}.
     */
    @WithName("default-write-permission")
    Optional<String> defaultWritePermission();

    /**
     * Fallback Kubernetes resource/verb applied when a requested {@code *:delete} permission name
     * has no explicit entry in the {@code permissions} map, and is checked before {@link
     * #defaultPermission()}. Follows the same {@code [resource(/subresource)]:verb} format, e.g.
     * {@code pods:delete}.
     */
    @WithName("default-delete-permission")
    Optional<String> defaultDeletePermission();

    /**
     * Global fallback Kubernetes resource/verb applied when a requested permission name has no
     * explicit entry in the {@code permissions} map and no matching verb-level default. Follows the
     * same {@code [resource(/subresource)]:verb} format, e.g. {@code pods/exec:create}. When
     * absent, an unmapped permission is denied.
     */
    @WithName("default-permission")
    Optional<String> defaultPermission();

    /**
     * Kubernetes namespace in which to perform namespace-scoped access reviews. When set
     * (non-empty), access reviews are performed as namespace-bound Roles in the specified
     * namespace. When empty (default), access reviews are performed as cluster-scoped ClusterRoles.
     * This property should be set by the Operator or Helm chart to the namespace where Cryostat is
     * installed.
     */
    @WithName("namespace")
    Optional<String> namespace();

    CacheConfig cache();

    interface CacheConfig {
        @WithName("expire-after-access")
        Duration expireAfterAccess();

        @WithName("maximum-size")
        long maximumSize();
    }

    DecisionCacheConfig decisionCache();

    interface DecisionCacheConfig {
        /**
         * How long a SSAR decision is retained before expiry. The TTL is measured from the time the
         * entry was written; reads do not reset it. Defaults to 1 minute.
         */
        @WithName("ttl")
        Duration ttl();

        @WithName("maximum-size")
        long maximumSize();
    }
}
