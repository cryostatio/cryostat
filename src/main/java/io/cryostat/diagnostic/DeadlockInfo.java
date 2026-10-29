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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A detected deadlock cycle and the threads participating in it.
 *
 * <p>Projected from {@link me.bechberger.jthreaddump.model.DeadlockInfo} so that the parser's model
 * does not appear in the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeadlockInfo(List<DeadlockedThread> threads) {

    public DeadlockInfo {
        threads = threads != null ? List.copyOf(threads) : List.of();
    }

    public static DeadlockInfo from(me.bechberger.jthreaddump.model.DeadlockInfo info) {
        return new DeadlockInfo(info.threads().stream().map(DeadlockedThread::from).toList());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadlockedThread(
            String threadName,
            String waitingForMonitor,
            String waitingForObject,
            String waitingForObjectType,
            String heldBy,
            List<StackFrame> stackTrace,
            List<LockInfo> locks) {

        public DeadlockedThread {
            stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
            locks = locks != null ? List.copyOf(locks) : List.of();
        }

        static DeadlockedThread from(
                me.bechberger.jthreaddump.model.DeadlockInfo.DeadlockedThread thread) {
            return new DeadlockedThread(
                    thread.threadName(),
                    thread.waitingForMonitor(),
                    thread.waitingForObject(),
                    thread.waitingForObjectType(),
                    thread.heldBy(),
                    thread.stackTrace().stream().map(StackFrame::from).toList(),
                    thread.locks().stream().map(LockInfo::from).toList());
        }
    }
}
