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
package io.cryostat.credentials.events;

import io.cryostat.credentials.Credential;
import io.cryostat.events.EntityNotificationObserver;
import io.cryostat.expressions.MatchExpression;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class CredentialNotificationObserver extends EntityNotificationObserver.Simple<Credential> {

    void onCredentialCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    CredentialEvents.CredentialCreated event) {
        handleCreated(event);
    }

    void onCredentialUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    CredentialEvents.CredentialUpdated event) {
        handleUpdated(event);
    }

    void onCredentialDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    CredentialEvents.CredentialDeleted event) {
        handleDeleted(event);
    }

    @Override
    protected <S> Object buildPayload(S snapshot) {
        CredentialEvents.CredentialSnapshot credentialSnapshot =
                (CredentialEvents.CredentialSnapshot) snapshot;
        return Credential.findById(credentialSnapshot.id());
    }

    @Override
    protected <S> Object buildDeletedPayload(S snapshot) {
        // reconstruct from snapshot since entity no longer exists
        CredentialEvents.CredentialSnapshot credentialSnapshot =
                (CredentialEvents.CredentialSnapshot) snapshot;
        return snapshotToCredential(credentialSnapshot);
    }

    @Override
    protected <S> Object buildDeletedEventPayload(S snapshot) {
        // reconstruct from snapshot since entity no longer exists
        CredentialEvents.CredentialSnapshot credentialSnapshot =
                (CredentialEvents.CredentialSnapshot) snapshot;
        return snapshotToCredential(credentialSnapshot);
    }

    private Credential snapshotToCredential(CredentialEvents.CredentialSnapshot snapshot) {
        Credential credential = new Credential();
        credential.id = snapshot.id();
        // reconstruct from snapshot since it may have been cascade-deleted
        MatchExpression matchExpression = new MatchExpression(snapshot.matchExpressionScript());
        matchExpression.id = snapshot.matchExpressionId();
        credential.matchExpression = matchExpression;
        return credential;
    }
}
