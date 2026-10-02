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
package io.cryostat.diagnostic;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A lock or synchronizer held or awaited by a thread in a thread dump.
 *
 * <p>Projected from {@link me.bechberger.jthreaddump.model.LockInfo} so that the parser's model
 * does not appear in the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockInfo(
        String lockId, String className, LockOperation operation, String ownerThreadId) {

    public enum LockOperation {
        LOCKED,
        WAITING_TO_LOCK,
        WAITING_ON,
        PARKING,
        ELIMINATED,
        ;

        static LockOperation from(me.bechberger.jthreaddump.model.LockInfo.LockOperation op) {
            return op == null ? null : valueOf(op.name());
        }
    }

    public static LockInfo from(me.bechberger.jthreaddump.model.LockInfo lock) {
        return new LockInfo(
                lock.lockId(),
                lock.className(),
                LockOperation.from(lock.operation()),
                lock.ownerThreadId());
    }
}
