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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

public class URIRangeTest {

    @Test
    void testLoopbackRangeValid() throws Exception {
        URI uri = new URI("http://127.0.0.1");
        assertTrue(URIRange.LOOPBACK.validate(uri), "Should validate loopback address");
    }

    @Test
    void testLoopbackRangeInvalid() throws Exception {
        URI uri = new URI("http://192.168.0.1");
        assertFalse(URIRange.LOOPBACK.validate(uri), "Should not validate non-loopback address");
    }

    @Test
    void testLinkLocalRangeValid() throws Exception {
        URI uri = new URI("http://169.254.0.1");
        assertTrue(URIRange.LINK_LOCAL.validate(uri), "Should validate link-local address");
    }

    @Test
    void testLinkLocalRangeInvalid() throws Exception {
        URI uri = new URI("http://192.168.0.1");
        assertFalse(
                URIRange.LINK_LOCAL.validate(uri), "Should not validate non-link-local address");
    }

    @Test
    void testSiteLocalRangeValid() throws Exception {
        URI uri = new URI("http://192.168.1.100");
        assertTrue(URIRange.SITE_LOCAL.validate(uri), "Should validate site-local address");
    }

    @Test
    void testSiteLocalRangeInvalid() throws Exception {
        URI uri = new URI("http://8.8.8.8");
        assertFalse(
                URIRange.SITE_LOCAL.validate(uri), "Should not validate non-site-local address");
    }

    @Test
    void testDnsLocalRangeValid() throws Exception {
        URI uri = new URI("http://hostname.local");
        assertTrue(URIRange.DNS_LOCAL.validate(uri), "Should validate DNS local address");
    }

    @Test
    void testDnsLocalRangeInvalid() throws Exception {
        URI uri = new URI("http://example.com");
        assertFalse(URIRange.DNS_LOCAL.validate(uri), "Should not validate non-DNS local address");
    }

    @Test
    void testPublicRangeAlwaysTrue() throws Exception {
        URI uri = new URI("http://example.com");
        assertTrue(URIRange.PUBLIC.validate(uri), "Public range should always return true");
    }
}
