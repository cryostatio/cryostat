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

/** Specifies how a named jvmId accessor is retrieved from a response collection item. */
public enum AccessorType {
    /** The jvmId is exposed as a public instance field (e.g. {@code item.jvmId}). */
    FIELD,
    /**
     * The jvmId is exposed as a no-arg instance method (e.g. a record accessor {@code
     * item.jvmId()}).
     */
    METHOD
}
