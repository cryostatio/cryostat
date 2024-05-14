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
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum URIRange {
    LOOPBACK(u -> check(u, u2 -> true, InetAddress::isLoopbackAddress)),
    LINK_LOCAL(
            u ->
                    check(
                            u,
                            u2 -> StringUtils.isNotBlank(u2.getHost()),
                            InetAddress::isLinkLocalAddress)),
    SITE_LOCAL(
            u ->
                    check(
                            u,
                            u2 -> StringUtils.isNotBlank(u2.getHost()),
                            InetAddress::isSiteLocalAddress)),
    DNS_LOCAL(
            u ->
                    StringUtils.isNotBlank(u.getHost())
                            && (u.getHost().endsWith(".local")
                                    || u.getHost().endsWith(".localhost"))),
    PUBLIC(u -> true),
    ;

    private static final Logger log = LoggerFactory.getLogger(URIRange.class);

    private URIRange(Predicate<URI> fn) {
        this.fn = fn;
    }

    private final Predicate<URI> fn;

    private static boolean check(URI uri, Predicate<URI> f1, Predicate<InetAddress> f2) {
        try {
            return f1.test(uri) && f2.test(InetAddress.getByName(uri.getHost()));
        } catch (UnknownHostException uhe) {
            log.error("Failed to resolve host", uhe);
            return false;
        }
    }

    private boolean test(URI uri) {
        return fn.test(uri);
    }

    public boolean validate(URI uri) {
        List<URIRange> ranges =
                List.of(URIRange.values()).stream()
                        .filter(r -> r.ordinal() <= this.ordinal())
                        .collect(Collectors.toList());
        boolean match = false;
        for (URIRange range : ranges) {
            match |= range.test(uri);
        }
        return match;
    }

    public static URIRange fromString(String s) {
        for (URIRange r : URIRange.values()) {
            if (r.name().equalsIgnoreCase(s)) {
                return r;
            }
        }
        return SITE_LOCAL;
    }

    /* public static class StringUtils {
        private StringUtils() {}

        public static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }

        public static boolean isNotBlank(String s) {
            return !isBlank(s);
        }

        public static String defaultValue(String in, String def) {
            return isNotBlank(in) ? in : def;
        }
    } */
}
