/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.credentials;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;

import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.core.eventbus.EventBus;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@EntityListeners(Credential.Listener.class)
public class Credential extends PanacheEntity {

    @Column(nullable = false, updatable = false)
    public String matchExpression;

    @ColumnTransformer(
            read = "pgp_sym_decrypt(username, current_setting('encrypt.key'))",
            write = "pgp_sym_encrypt(?, current_setting('encrypt.key'))")
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    public String username;

    @ColumnTransformer(
            read = "pgp_sym_decrypt(password, current_setting('encrypt.key'))",
            write = "pgp_sym_encrypt(?, current_setting('encrypt.key'))")
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    public String password;

    @ApplicationScoped
    static class Listener {

        @Inject EventBus bus;

        // TODO prePersist validate the matchExpression syntax

        @PostPersist
        public void postPersist(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsStored", Credentials.safeResult(credential)));
        }

        @PostUpdate
        public void postUpdate(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsUpdated", Credentials.safeResult(credential)));
        }

        @PostRemove
        public void postRemove(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsDeleted", Credentials.safeResult(credential)));
        }
    }
}
