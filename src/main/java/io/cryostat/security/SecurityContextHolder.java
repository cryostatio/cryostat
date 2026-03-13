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
package io.cryostat.security;

/**
 * Holds the current username in a ThreadLocal for access by components that cannot directly inject
 * security context, such as Hibernate Envers RevisionListener implementations.
 */
public class SecurityContextHolder {

    private static final ThreadLocal<String> usernameHolder = new ThreadLocal<>();

    private SecurityContextHolder() {
        // Utility class
    }

    /**
     * Store the username for the current thread.
     *
     * @param username the username to store
     */
    public static void setUsername(String username) {
        usernameHolder.set(username);
    }

    /**
     * Retrieve the username for the current thread.
     *
     * @return the username, or null if not set
     */
    public static String getUsername() {
        return usernameHolder.get();
    }

    /**
     * Clear the username for the current thread. Should be called after request processing to
     * prevent memory leaks.
     */
    public static void clearUsername() {
        usernameHolder.remove();
    }
}
