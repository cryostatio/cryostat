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

/**
 * Which of Cryostat's two inbound proxy paths a request arrived through, as determined by {@link
 * RequestProvenance#resolve}.
 *
 * <p>This is a statement about provenance only. It says nothing about who the caller is or what
 * they may do: that is {@link RbacHttpAuthenticationMechanism}'s decision, taken from this value
 * together with the configured {@link RbacMode}.
 */
enum ProvenancePath {
    /** Forwarded by the agent gateway, proven by possession of the agent gateway secret. */
    AGENT,

    /**
     * Forwarded by the user-path auth-strip proxy. Either proven by possession of the user proxy
     * secret, or inferred because no user proxy secret is configured for this deployment.
     */
    USER,

    /**
     * Carries neither stamp while stamps are expected, so it crossed neither proxy. Nothing about
     * such a request may be believed, including any identity headers it carries.
     */
    UNTRUSTED,
}
