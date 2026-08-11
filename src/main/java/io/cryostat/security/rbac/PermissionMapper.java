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

import java.util.Optional;

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
            return Optional.empty();
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
