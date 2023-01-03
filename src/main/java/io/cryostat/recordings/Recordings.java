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
package io.cryostat.recordings;

import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;

import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jdk.jfr.RecordingState;
import org.jboss.resteasy.reactive.RestPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("")
public class Recordings {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Inject TargetConnectionManager connectionManager;

    @GET
    @Path("/api/v1/recordings")
    @RolesAllowed("recording:read")
    public List<ArchivedRecording> listArchives() {
        return List.of();
    }

    @GET
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed({"recording:read", "target:read"})
    public List<ActiveRecording> listForTarget(@RestPath long id) throws Exception {
        Target target = Target.findById(id);
        if (target == null) {
            throw new NotFoundException();
        }
        return connectionManager.executeConnectedTask(
                target,
                conn -> {
                    return conn.getService().getAvailableRecordings().stream()
                            .map(
                                    desc -> {
                                        Metadata metadata = new Metadata(Map.of());
                                        return new ActiveRecording(
                                                desc.getId(),
                                                mapState(desc),
                                                desc.getDuration()
                                                        .in(UnitLookup.MILLISECOND)
                                                        .longValue(),
                                                desc.getStartTime()
                                                        .in(UnitLookup.MILLISECOND)
                                                        .longValue(),
                                                desc.isContinuous(),
                                                desc.getToDisk(),
                                                desc.getMaxSize().in(UnitLookup.BYTE).longValue(),
                                                desc.getMaxAge()
                                                        .in(UnitLookup.MILLISECOND)
                                                        .longValue(),
                                                desc.getName(),
                                                "TODO",
                                                "TODO",
                                                metadata);
                                    })
                            .toList();
                });
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/recordings")
    @RolesAllowed({"recording:read", "target:read"})
    public List<ActiveRecording> listForTargetByUrl(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return listForTarget(target.id);
    }

    private RecordingState mapState(IRecordingDescriptor desc) {
        switch (desc.getState()) {
            case CREATED:
                return RecordingState.NEW;
            case RUNNING:
                return RecordingState.RUNNING;
            case STOPPING:
                return RecordingState.RUNNING;
            case STOPPED:
                return RecordingState.STOPPED;
            default:
                logger.warn("Unrecognized recording state: {}", desc.getState());
                return RecordingState.CLOSED;
        }
    }

    public record ActiveRecording(
            long id,
            RecordingState state,
            long duration,
            long startTime,
            boolean continuous,
            boolean toDisk,
            long maxSize,
            long maxAge,
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata) {}

    public record ArchivedRecording(
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata,
            long size,
            long archivedTime) {}

    public record Metadata(Map<String, String> labels) {}
}
