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
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum URIRange {
    LOOPBACK(hostname -> check(hostname, InetAddress::isLoopbackAddress)),
    LINK_LOCAL(hostname -> check(hostname, InetAddress::isLinkLocalAddress)),
    SITE_LOCAL(hostname -> check(hostname, InetAddress::isSiteLocalAddress)),
    DNS_LOCAL(hostname -> hostname.endsWith(".local") || hostname.endsWith(".localhost")),
    PUBLIC(hostname -> true);

    private static final Logger log = LoggerFactory.getLogger(URIRange.class);
    private final Predicate<String> fn;

    private URIRange(Predicate<String> fn) {
        this.fn = fn;
    }

    private static boolean check(String hostname, Predicate<InetAddress> predicate) {
        try {
            InetAddress address = InetAddress.getByName(hostname);
            return predicate.test(address);
        } catch (UnknownHostException uhe) {
            log.error("Failed to resolve host", uhe);
            return false;
        }
    }

    public boolean validate(String hostname) {
        List<URIRange> ranges =
                List.of(values()).stream()
                        .filter(r -> r.ordinal() <= this.ordinal())
                        .collect(Collectors.toList());
        return ranges.stream().anyMatch(range -> range.fn.test(hostname));
    }

    public static URIRange fromString(String s) {
        for (URIRange range : URIRange.values()) {
            if (range.name().equalsIgnoreCase(s)) {
                return range;
            }
        }
        return SITE_LOCAL;
    }
}
