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
 * A single thread as captured in a thread dump.
 *
 * <p>Projected from {@link me.bechberger.jthreaddump.model.ThreadInfo} so that the parser's model
 * does not appear in the API. This also lets {@code state} be documented as a {@link ThreadState}
 * enum: the parser exposes {@code java.lang.Thread.State}, a JDK type that Jandex cannot index from
 * a dependency jar, so the schema scanner would fall back to an untyped object.
 *
 * <p>Most fields are null for VM-internal threads, hence the boxed types and null omission.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadInfo(
        String name,
        Long threadId,
        Long nativeId,
        Integer priority,
        Boolean daemon,
        ThreadState state,
        Double cpuTimeSec,
        Double elapsedTimeSec,
        List<StackFrame> stackTrace,
        List<LockInfo> locks,
        String additionalInfo,
        Long carryingVirtualThreadId) {

    public ThreadInfo {
        stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
        locks = locks != null ? List.copyOf(locks) : List.of();
    }

    public static ThreadInfo from(me.bechberger.jthreaddump.model.ThreadInfo thread) {
        return new ThreadInfo(
                thread.name(),
                thread.threadId(),
                thread.nativeId(),
                thread.priority(),
                thread.daemon(),
                ThreadState.from(thread.state()),
                thread.cpuTimeSec(),
                thread.elapsedTimeSec(),
                thread.stackTrace().stream().map(StackFrame::from).toList(),
                thread.locks().stream().map(LockInfo::from).toList(),
                thread.additionalInfo(),
                thread.carryingVirtualThreadId());
    }
}
