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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Provenance is decided from headers and configuration alone, so it can be exercised directly
 * rather than through a booted Quarkus instance. Each combination of configured secrets is a plain
 * constructor call here, where it would otherwise be a separate {@code @QuarkusTestProfile} and a
 * separate JVM boot.
 */
class RequestProvenanceTest {

    static final String GATEWAY_SECRET = "gateway-secret-value";
    static final String USER_PROXY_SECRET = "user-proxy-secret-value";

    static RequestProvenance bothConfigured() {
        return new RequestProvenance(Optional.of(GATEWAY_SECRET), Optional.of(USER_PROXY_SECRET));
    }

    @Nested
    class WithBothSecretsConfigured {

        @Test
        void resolvesAgentPathOnValidGatewayStamp() {
            var ctx = MockRequests.context(ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET);
            assertEquals(ProvenancePath.AGENT, bothConfigured().resolve(ctx));
        }

        @Test
        void resolvesUserPathOnValidUserStamp() {
            var ctx = MockRequests.context(ProxyHeaders.USER_PROXY_AUTH, USER_PROXY_SECRET);
            assertEquals(ProvenancePath.USER, bothConfigured().resolve(ctx));
        }

        @Test
        void rejectsRequestCarryingNeitherStamp() {
            var ctx = MockRequests.context(ProxyHeaders.FORWARDED_USER, "admin");
            assertEquals(ProvenancePath.UNTRUSTED, bothConfigured().resolve(ctx));
        }

        @Test
        void rejectsWrongGatewayStampValue() {
            var ctx = MockRequests.context(ProxyHeaders.AGENT_AUTH, "not-the-secret");
            assertEquals(ProvenancePath.UNTRUSTED, bothConfigured().resolve(ctx));
        }

        @Test
        void rejectsWrongUserStampValue() {
            var ctx = MockRequests.context(ProxyHeaders.USER_PROXY_AUTH, "not-the-secret");
            assertEquals(ProvenancePath.UNTRUSTED, bothConfigured().resolve(ctx));
        }

        /**
         * {@code proxy_set_header X "";} makes nginx omit the header, but a blank value must fail
         * regardless: the test is equality against the secret, never presence.
         */
        @Test
        void rejectsBlankStampValues() {
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    bothConfigured().resolve(MockRequests.context(ProxyHeaders.AGENT_AUTH, "")));
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    bothConfigured()
                            .resolve(MockRequests.context(ProxyHeaders.USER_PROXY_AUTH, "")));
        }

        /** Each hop clears the other's stamp, so a stamp on the wrong header proves nothing. */
        @Test
        void rejectsEachSecretPresentedOnTheOtherPathsHeader() {
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    bothConfigured()
                            .resolve(
                                    MockRequests.context(
                                            ProxyHeaders.AGENT_AUTH, USER_PROXY_SECRET)));
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    bothConfigured()
                            .resolve(
                                    MockRequests.context(
                                            ProxyHeaders.USER_PROXY_AUTH, GATEWAY_SECRET)));
        }

        /**
         * The two are mutually exclusive by construction, so a request carrying both means a hop
         * was bypassed. The agent stamp is stripped and the request falls through to the user path.
         */
        @Test
        void stripsAgentStampWhenForwardedUserAlsoPresent() {
            var ctx =
                    MockRequests.context(
                            ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET,
                            ProxyHeaders.USER_PROXY_AUTH, USER_PROXY_SECRET,
                            ProxyHeaders.FORWARDED_USER, "admin");

            assertEquals(ProvenancePath.USER, bothConfigured().resolve(ctx));
            assertNull(ctx.request().getHeader(ProxyHeaders.AGENT_AUTH));
        }
    }

    @Nested
    class WithNoGatewaySecret {

        static RequestProvenance provenance() {
            return new RequestProvenance(Optional.empty(), Optional.of(USER_PROXY_SECRET));
        }

        /** Fail closed: with no secret configured the Agent principal is simply unreachable. */
        @Test
        void neverResolvesAgentPath() {
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    provenance()
                            .resolve(
                                    MockRequests.context(ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET)));
            assertEquals(
                    ProvenancePath.UNTRUSTED,
                    provenance()
                            .resolve(MockRequests.context(ProxyHeaders.AGENT_AUTH, "anything")));
        }

        @Test
        void stillResolvesUserPath() {
            var ctx = MockRequests.context(ProxyHeaders.USER_PROXY_AUTH, USER_PROXY_SECRET);
            assertEquals(ProvenancePath.USER, provenance().resolve(ctx));
        }
    }

    @Nested
    class WithNoUserProxySecret {

        static RequestProvenance provenance() {
            return new RequestProvenance(Optional.of(GATEWAY_SECRET), Optional.empty());
        }

        /**
         * The default deployment posture: no auth-strip hop exists to apply a stamp, so an
         * unstamped request falls back to inference rather than rejection. Fail open, but selected
         * by configuration rather than by anything on the request.
         */
        @Test
        void fallsBackToInferenceForUnstampedRequests() {
            assertEquals(ProvenancePath.USER, provenance().resolve(MockRequests.context()));
            assertEquals(
                    ProvenancePath.USER,
                    provenance()
                            .resolve(MockRequests.context(ProxyHeaders.FORWARDED_USER, "admin")));
        }

        @Test
        void stillResolvesAgentPath() {
            var ctx = MockRequests.context(ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET);
            assertEquals(ProvenancePath.AGENT, provenance().resolve(ctx));
        }
    }

    @Nested
    class WithNeitherSecret {

        static RequestProvenance provenance() {
            return new RequestProvenance(Optional.empty(), Optional.empty());
        }

        @Test
        void treatsEverythingAsTheUserPath() {
            assertEquals(ProvenancePath.USER, provenance().resolve(MockRequests.context()));
            assertEquals(
                    ProvenancePath.USER,
                    provenance()
                            .resolve(
                                    MockRequests.context(ProxyHeaders.AGENT_AUTH, GATEWAY_SECRET)));
        }
    }

    /**
     * Every case lives in a {@code @Nested} class, including this one-test group. Surefire's
     * class-level {@code -Dtest} filter silently skips a test declared directly on a class that has
     * nested classes, so an outer-class test here would report as passing coverage while never
     * having run.
     */
    @Nested
    class WithBlankConfiguredSecrets {

        /** A blank configured value is no configured value: it must not become a usable stamp. */
        @Test
        void treatsBlankConfiguredSecretsAsUnset() {
            var provenance = new RequestProvenance(Optional.of("   "), Optional.of(""));
            assertEquals(
                    ProvenancePath.USER,
                    provenance.resolve(MockRequests.context(ProxyHeaders.AGENT_AUTH, "   ")));
        }
    }
}
