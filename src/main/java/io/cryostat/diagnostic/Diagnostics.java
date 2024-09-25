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

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;

import javax.management.openmbean.CompositeData;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import com.fasterxml.jackson.annotation.JsonValue;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/beta/diagnostics/targets/{targetId}")
public class Diagnostics {

    @Inject TargetConnectionManager targetConnectionManager;

    @Path("/gc")
    @RolesAllowed("write")
    @Blocking
    @POST
    public void gc(@RestPath long targetId) {
        targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn ->
                        conn.invokeMBeanOperation(
                                "java.lang:type=Memory", "gc", null, null, Void.class));
    }

    @Path("/threads")
    @RolesAllowed("write")
    @Blocking
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @GET
    public ThreadDump dumpThreads(@RestPath long targetId) {
        return targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn -> {
                    CompositeData[] cd =
                            conn.invokeMBeanOperation(
                                    "java.lang:type=Threading",
                                    "dumpAllThreads",
                                    new Object[] {true, true},
                                    new String[] {boolean.class.getName(), boolean.class.getName()},
                                    CompositeData[].class);
                    return new ThreadDump(List.of(cd).stream().map(ThreadInfo::from).toList());
                });
    }

    static record ThreadDump(@JsonValue List<ThreadInfo> threadInfos) {
        ThreadDump(List<ThreadInfo> threadInfos) {
            this.threadInfos = new ArrayList<>(threadInfos);
        }

        // FIXME this should return the same text format as jcmd/jstack commands do, ie match the
        // HotSpotDiagnosticMXBean dumpThreads operation.
        // 2024-09-10 12:36:07
        // Full thread dump OpenJDK 64-Bit Server VM (17.0.12+7 mixed mode, sharing):

        // Threads class SMR info:
        // _java_thread_list=0x00007fb9400018a0, length=15, elements={
        // 0x00007fba080278e0, 0x00007fba082158c0, 0x00007fba08216cb0, 0x00007fba0821c1d0,
        // 0x00007fba0821d600, 0x00007fba0821ea20, 0x00007fba082203c0, 0x00007fba08221900,
        // 0x00007fba08222d80, 0x00007fba0822a4d0, 0x00007fba0822e3d0, 0x00007fba0832b470,
        // 0x00007fba08409550, 0x00007fba08630d50, 0x00007fb940000eb0
        // }

        // "main" #1 prio=5 os_prio=0 cpu=395.16ms elapsed=4884.90s allocated=50569K
        // defined_classes=2034 tid=0x00007fba080278e0 nid=0x391297 in Object.wait()
        // [0x00007fba0e5fe000]
        //    java.lang.Thread.State: WAITING (on object monitor)
        // 	at java.lang.Object.wait(java.base@17.0.12/Native Method)
        // 	- waiting on <0x000000045d468440> (a java.lang.ProcessImpl)
        // 	at java.lang.Object.wait(java.base@17.0.12/Object.java:338)
        // 	at java.lang.ProcessImpl.waitFor(java.base@17.0.12/ProcessImpl.java:434)
        // 	- locked <0x000000045d468440> (a java.lang.ProcessImpl)
        // 	at io.quarkus.cli.build.ExecuteUtil.executeProcess(ExecuteUtil.java:73)
        // 	at io.quarkus.cli.build.BuildSystemRunner.run(BuildSystemRunner.java:50)
        // 	at io.quarkus.cli.Dev.call(Dev.java:46)
        // 	at io.quarkus.cli.Dev.call(Dev.java:18)
        // 	at picocli.CommandLine.executeUserObject(CommandLine.java:2045)
        // 	at picocli.CommandLine.access$1500(CommandLine.java:148)
        // 	at
        // picocli.CommandLine$RunLast.executeUserObjectOfLastSubcommandWithSameParent(CommandLine.java:2465)
        // 	at picocli.CommandLine$RunLast.handle(CommandLine.java:2457)
        // 	at picocli.CommandLine$RunLast.handle(CommandLine.java:2419)
        // 	at picocli.CommandLine$AbstractParseResultHandler.execute(CommandLine.java:2277)
        // 	at picocli.CommandLine$RunLast.execute(CommandLine.java:2421)
        // 	at picocli.CommandLine.execute(CommandLine.java:2174)
        // 	at io.quarkus.cli.QuarkusCli.run(QuarkusCli.java:116)
        // 	at
        // io.quarkus.runtime.ApplicationLifecycleManager.run(ApplicationLifecycleManager.java:140)
        // 	at io.quarkus.runtime.Quarkus.run(Quarkus.java:71)
        // 	at io.quarkus.runtime.Quarkus.run(Quarkus.java:44)
        // 	at io.quarkus.cli.Main.main(Main.java:9)

        //    Locked ownable synchronizers:
        // 	- None
        //
        // 	... (trimmed) ...
        //
        // 	"G1 Service" os_prio=0 cpu=256.32ms elapsed=4884.90s tid=0x00007fba081ddbf0 nid=0x39129c
        // runnable

        // "VM Periodic Task Thread" os_prio=0 cpu=743.47ms elapsed=4884.89s tid=0x00007fba0822be20
        // nid=0x3912a7 waiting on condition

        // JNI global refs: 24, weak refs: 0
        @Override
        public String toString() {
            var sb = new StringBuilder();
            this.threadInfos.forEach(sb::append);
            return sb.toString();
        }
    }
}
