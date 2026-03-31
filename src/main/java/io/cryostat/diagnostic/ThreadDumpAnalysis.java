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

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import me.bechberger.jthreaddump.model.DeadlockInfo;
import me.bechberger.jthreaddump.model.JniInfo;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

public class ThreadDumpAnalysis {

    public List<Entry<State, Long>> aggregateThreadStates;
    public List<Entry<String, Long>> aggregateLockInfo;
    public List<Entry<List<StackFrame>, Long>> aggregateStackTraces;
    public List<Entry<String, Long>> runningMethods;
    public List<DeadlockInfo> deadlockInfos;
    public List<ThreadInfo> threads;
    public List<AnalysisResult> specificFindings;
    public JniInfo jniInfo;
    public String jvmInfo;

    public ThreadDumpAnalysis(ThreadDump dump) {
        this.specificFindings = new ArrayList<>();
        this.jniInfo = dump.jniInfo();
        this.jvmInfo = dump.jvmInfo();
        this.deadlockInfos = dump.deadlockInfos();
        this.threads = dump.threads();
        analyzeThreadDump(dump);
    }

    void analyzeThreadDump(ThreadDump dump) {
        int copyOfCount = 0;
        int logCount = 0;
        int dataSourceContention = 0;
        int strictMaxCount = 0;
        Map<State, Long> threadStates =
                new HashMap<>(
                        Map.of(
                                State.BLOCKED, 0l,
                                State.NEW, 0l,
                                State.RUNNABLE, 0l,
                                State.TERMINATED, 0l,
                                State.TIMED_WAITING, 0l,
                                State.WAITING, 0l));
        ;
        Map<String, Long> lockInfo = new HashMap<>();
        Map<List<StackFrame>, Long> stackTraces = new HashMap<>();
        Map<String, Long> aggregateMethods = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            // Populate the aggregate thread states map
            // Thread state, along with several other fields are null for VM Threads
            if (Objects.nonNull(t.state())) {
                threadStates.put(t.state(), Long.valueOf(threadStates.get(t.state()) + 1));
            }
            // Populate the aggregate synchronizers map
            for (LockInfo l : t.locks()) {
                lockInfo.merge(l.className(), 1l, Long::sum);
            }
            // Populate the aggregate stack traces map and method map
            stackTraces.merge(t.stackTrace(), 1l, Long::sum);
            if (!t.stackTrace().isEmpty()) {
                System.out.println("Stack Frame: " + t.stackTrace().getFirst());
                String method = t.stackTrace().getFirst().methodName();
                if (t.state() == State.RUNNABLE) {
                    aggregateMethods.merge(method, 1l, Long::sum);
                }
                // General Findings
                if (method.equals("java.util.Arrays.copyOf")) {
                    copyOfCount++;
                }
                // JBoss Specific Findings (from yatda)
                // Check log contention
                if (method.equals("org.jboss.logmanager.handlers.WriterHandler.doPublish")) {
                    logCount++;
                }
                // Check datasource exhaustion
                if (method.equals(
                        "org.jboss.jca.core.connectionmanager.pool.api.Semaphore.tryAcquire")) {
                    dataSourceContention++;
                }
                // Check EJB strict max pool exhaustion
                if (method.equals("org.jboss.as.ejb3.pool.strictmax.StrictMaxPool.get")) {
                    strictMaxCount++;
                }
            }
        }
        if (copyOfCount > 0) {
            specificFindings.add(
                    new AnalysisResult(
                            "java.util.Arrays.copyOf calls",
                            String.format(
                                    "The amount of threads in java.util.Arrays.copyOf is {0}."
                                        + " Notable amounts of threads here or a significant time"
                                        + " spent here in any thread may indicate a lot of time"
                                        + " blocked in safe point pausing for GC because of little"
                                        + " free heap space or the Array copies and other activity"
                                        + " generating excessive amounts of temporary heap garbage."
                                        + "  GC logs should be reviewed to confirm or rule out GC"
                                        + " performance concerns.",
                                    copyOfCount),
                            1));
        }
        if (logCount > 0) {
            specificFindings.add(
                    new AnalysisResult(
                            "Log Contention",
                            String.format(
                                    "The amount of threads in"
                                        + " org.jboss.logmanager.handlers.WriterHandler.doPublish"
                                        + " is {0}.  High amounts of threads here may indicate"
                                        + " logging that is too verbose and/or log writes that are"
                                        + " too slow.  Consider decreasing log verbosity or"
                                        + " configure an async log handler"
                                        + " (https://access.redhat.com/solutions/444033) to limit"
                                        + " response time impacts from log writes.\"",
                                    logCount),
                            1));
        }
        if (dataSourceContention > 0) {
            specificFindings.add(
                    new AnalysisResult(
                            "Datasource Exhaustion",
                            String.format(
                                    "The amount of threads waiting for a datasource connection in"
                                        + " org.jboss.jca.core.connectionmanager.pool.api.Semaphore.tryAcquire"
                                        + " is {0}.  This indicates a datasource pool needs to be"
                                        + " increased for the load or connections are being leaked"
                                        + " or used too long"
                                        + " (https://access.redhat.com/solutions/17782).",
                                    dataSourceContention),
                            1));
        }
        if (strictMaxCount > 0) {
            specificFindings.add(
                    new AnalysisResult(
                            "EJB strict max pool exhaustion",
                            String.format(
                                    "The amount of threads waiting for an EJB instance in"
                                        + " org.jboss.as.ejb3.pool.strictmax.StrictMaxPool.get is"
                                        + " $COUNT.  This indicates an EJB instance pool needs to"
                                        + " be increased for the load"
                                        + " (https://access.redhat.com/solutions/255033).  Check"
                                        + " other threads actively processing in"
                                        + " org.jboss.as.ejb3.component.pool.PooledInstanceInterceptor.processInvocation"
                                        + " to see if EJB instances are used up in any specific"
                                        + " calls.",
                                    strictMaxCount),
                            1));
        }
        this.aggregateThreadStates = new ArrayList<Entry<State, Long>>(threadStates.entrySet());
        this.aggregateLockInfo = new ArrayList<Entry<String, Long>>(lockInfo.entrySet());
        this.aggregateStackTraces =
                new ArrayList<Entry<List<StackFrame>, Long>>(stackTraces.entrySet());
        this.runningMethods = new ArrayList<Entry<String, Long>>(aggregateMethods.entrySet());
    }

    public record AnalysisResult(String resultName, String explanation, int score) {}
    ;
}
