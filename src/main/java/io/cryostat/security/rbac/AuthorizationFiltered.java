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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource method as a Category 3 aggregate listing endpoint whose response
 * collection must be post-filtered per item according to namespace-scoped SSAR decisions.
 *
 * <p>Applied to methods that return {@code List<T>} or {@code Collection<T>} where each element
 * spans potentially different Kubernetes namespaces. The {@link
 * AuthorizationFilteredResponseFilter} intercepts the response post-handler but pre-serialisation,
 * resolves the namespace for each item via its jvmId, and retains only the items for which the
 * caller holds the declared permission in that namespace.
 *
 * <p>In {@link RbacMode#PERMISSIVE} and {@link RbacMode#BASIC} modes the filter is a no-op and the
 * full unfiltered collection is returned.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorizationFiltered {

    /**
     * The Cryostat resource type, e.g. {@code "targets"} or {@code "archivedrecordings"}. Combined
     * with {@link #verb()} to form the {@code resource:verb} permission checked per item.
     */
    String resourceType();

    /**
     * The RBAC verb, e.g. {@code "read"}. Combined with {@link #resourceType()} to form the {@code
     * resource:verb} permission checked per item.
     */
    String verb();

    /**
     * Name of the field or method on each collection item that returns the jvmId {@code String}.
     * Use in conjunction with {@link #jvmIdAccessorType()} to indicate whether this is a field
     * access or a method invocation.
     */
    String jvmIdAccessorName();

    /**
     * Whether the jvmId is accessed via a public field ({@link AccessorType#FIELD}) or a no-arg
     * method ({@link AccessorType#METHOD}, e.g. a record accessor).
     */
    AccessorType jvmIdAccessorType();

    /**
     * Optional additional {@code resource:verb} permission strings to check per item in the same
     * resolved namespace. All permissions must be granted for the item to be included. Defaults to
     * empty (no additional checks).
     */
    String[] additionalPermissions() default {};
}
