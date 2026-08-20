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

import java.security.Permission;
import java.util.Optional;

import io.quarkus.security.StringPermission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

/**
 * Maps Cryostat fine-grained permission names to Kubernetes resource/subresource/verb triples used
 * in SelfSubjectAccessReview calls.
 *
 * <p>Permission name syntax: {@code resource:verb} (colon-separated), e.g. {@code
 * activerecordings:read}.
 *
 * <p>Configuration key syntax: dot-separated, e.g. {@code activerecordings.read}, stored under
 * {@code cryostat.security.rbac.permissions."activerecordings.read"}.
 *
 * <p>Configuration value syntax: {@code resource(/subresource):verb}, e.g. {@code pods/exec:create}
 * or {@code pods:get}.
 */
@ApplicationScoped
public class PermissionMapper {

    @Inject RbacConfig config;

    /**
     * Resolve the Kubernetes resource/subresource/verb for a given Cryostat permission name.
     *
     * <p>Resolution order:
     *
     * <ol>
     *   <li>Explicit entry in {@code cryostat.security.rbac.permissions} for this name.
     *   <li>Verb-level default: {@code cryostat.security.rbac.default-read-permission}, {@code
     *       cryostat.security.rbac.default-write-permission}, or {@code
     *       cryostat.security.rbac.default-delete-permission}, matched on the action suffix of the
     *       permission name. Unrecognised verbs skip this step.
     *   <li>The value of {@code cryostat.security.rbac.default-permission}, if set.
     *   <li>{@code Optional.empty()} — caller treats this as deny.
     * </ol>
     *
     * @param permissionName colon-separated permission, e.g. {@code activerecordings:read}
     * @return the resolved K8s mapping, or empty if no mapping is configured for this permission
     */
    public Optional<K8sResourceVerb> resolve(String permissionName) {
        if (StringUtils.isBlank(permissionName)) {
            return Optional.empty();
        }
        String configKey = permissionName.replace(':', '.');
        String value = config.permissions().get(configKey);
        if (StringUtils.isBlank(value)) {
            int colonIdx = permissionName.lastIndexOf(':');
            if (colonIdx >= 0) {
                String verb = permissionName.substring(colonIdx + 1);
                Optional<String> verbDefault =
                        switch (verb) {
                            case "read" -> config.defaultReadPermission();
                            case "write" -> config.defaultWritePermission();
                            case "delete" -> config.defaultDeletePermission();
                            default -> Optional.empty();
                        };
                if (verbDefault.isPresent()) {
                    return verbDefault.map(this::parse);
                }
            }
            return config.defaultPermission().map(this::parse);
        }
        return Optional.of(parse(value));
    }

    private K8sResourceVerb parse(String value) {
        int colonIdx = value.lastIndexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException(
                    "RBAC permission mapping value must follow [resource(/subresource)]:verb"
                            + " format, got: "
                            + value);
        }
        String resourcePart = value.substring(0, colonIdx);
        String verb = value.substring(colonIdx + 1);

        int slashIdx = resourcePart.indexOf('/');
        String resource;
        String subresource;
        if (slashIdx >= 0) {
            resource = resourcePart.substring(0, slashIdx);
            subresource = resourcePart.substring(slashIdx + 1);
        } else {
            resource = resourcePart;
            subresource = "";
        }
        return new K8sResourceVerb(resource, subresource, verb);
    }

    /**
     * Returns a {@link Permission} for {@code resource} and {@code action}, suitable for passing to
     * {@link io.quarkus.security.identity.SecurityIdentity#checkPermission}. The assembled
     * permission name follows the {@code resource:action} convention used throughout Cryostat.
     *
     * @param resource the resource part of the permission, e.g. {@code archivedrecordings}
     * @param action the action part of the permission, e.g. {@code write}
     * @return a {@code Permission} whose name is {@code resource:action}
     */
    public static Permission toPermission(String resource, String action) {
        return new StringPermission(resource, action);
    }

    /**
     * Parses a {@code resource:action} permission name and returns the corresponding {@link
     * Permission}, suitable for passing to {@link
     * io.quarkus.security.identity.SecurityIdentity#checkPermission}.
     *
     * @param permissionName colon-separated permission name, e.g. {@code archivedrecordings:write}
     * @return a {@code Permission} equivalent to {@code toPermission(resource, action)}
     * @throws IllegalArgumentException if {@code permissionName} does not contain a colon
     */
    public static Permission toPermission(String permissionName) {
        int colon = permissionName.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "Permission must follow resource:action format: " + permissionName);
        }
        return toPermission(
                permissionName.substring(0, colon), permissionName.substring(colon + 1));
    }

    /**
     * Holds the decomposed Kubernetes resource, optional subresource, and verb for a SSAR request.
     */
    public record K8sResourceVerb(String resource, String subresource, String verb) {
        public K8sResourceVerb {
            if (StringUtils.isBlank(resource)) {
                throw new IllegalArgumentException("resource must not be blank");
            }
            if (StringUtils.isBlank(verb)) {
                throw new IllegalArgumentException("verb must not be blank");
            }
            if (subresource == null) {
                subresource = "";
            }
        }
    }
}
