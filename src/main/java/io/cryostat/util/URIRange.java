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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public enum URIRange {
    LOOPBACK(
            uri -> {
                try {
                    return uri.getHost() != null
                            && InetAddress.getByName(uri.getHost()).isLoopbackAddress();
                } catch (UnknownHostException e) {
                    return false;
                }
            }),
    LINK_LOCAL(
            uri -> {
                try {
                    return uri.getHost() != null
                            && InetAddress.getByName(uri.getHost()).isLinkLocalAddress();
                } catch (UnknownHostException e) {
                    return false;
                }
            }),
    SITE_LOCAL(
            uri -> {
                try {
                    return uri.getHost() != null
                            && InetAddress.getByName(uri.getHost()).isSiteLocalAddress();
                } catch (UnknownHostException e) {
                    return false;
                }
            }),
    DNS_LOCAL(
            uri -> {
                return uri.getHost() != null
                        && (uri.getHost().endsWith(".local")
                                || uri.getHost().endsWith(".localhost"));
            }),
    PUBLIC(uri -> true);

    private static final Logger logger = Logger.getLogger(URIRange.class.getName());
    private final Predicate<URI> validation;

    URIRange(Predicate<URI> validation) {
        this.validation = validation;
    }

    private static boolean isAddressType(URI uri, Predicate<InetAddress> addressTypeChecker) {
        try {
            return uri.getHost() != null
                    && addressTypeChecker.test(InetAddress.getByName(uri.getHost()));
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, "DNS resolution failed for host: " + uri.getHost(), e);
            return false;
        }
    }

    private static boolean isLoopback(URI uri) {
        return isAddressType(uri, InetAddress::isLoopbackAddress);
    }

    private static boolean isLinkLocal(URI uri) {
        return isAddressType(uri, InetAddress::isLinkLocalAddress);
    }

    private static boolean isSiteLocal(URI uri) {
        return isAddressType(uri, InetAddress::isSiteLocalAddress);
    }

    public boolean validate(URI uri) {
        return validation.test(uri);
    }

    public static URIRange fromString(String name) {
        for (URIRange r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown URIRange: " + name);
    }
}
